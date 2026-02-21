package com.neobank.fraud;

import com.neobank.fraud.model.FraudAlert;
import com.neobank.fraud.model.Transaction;
import com.neobank.fraud.serde.TransactionDeserializer;
import com.neobank.fraud.sink.LandingZoneSink;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * NeoBank Fraud Detector — Apache Flink streaming job.
 *
 * Pipeline:
 *   Kafka (transactions) → keyBy(account_id)
 *                        → 1-minute tumbling window
 *                        → sum(amount) > 10,000 MYR → PostgreSQL fraud_alerts
 *                        → every raw transaction    → local landing zone (JSONL)
 *
 * Build:   mvn clean package  (in flink-job/)
 * Submit:  flink run -c com.neobank.fraud.FraudDetectorJob target/flink-job-1.0-SNAPSHOT.jar
 *   or upload the JAR via the Flink UI at http://localhost:8081
 */
public class FraudDetectorJob {

    private static final Logger LOG = LoggerFactory.getLogger(FraudDetectorJob.class);

    // ── Configuration ──────────────────────────────────────────────────────────
    // Use Docker-internal hostnames — this job runs inside the Flink TaskManager container.
    // The Spring Boot producer (on host) uses localhost:9092; Flink uses kafka-broker:9094.
    private static final String KAFKA_BROKERS  = "kafka-broker:9094";
    private static final String KAFKA_TOPIC    = "transactions";
    private static final String KAFKA_GROUP    = "fraud-detector";

    // postgres-db:5432 is the internal Docker address; host clients use localhost:5433.
    private static final String PG_URL         = "jdbc:postgresql://postgres-db:5432/bank_db";
    private static final String PG_DRIVER      = "org.postgresql.Driver";
    private static final String PG_USER        = "postgres";
    private static final String PG_PASSWORD    = "postgres";

    private static final String LANDING_ZONE   = "/tmp/landing-zone";

    private static final double FRAUD_THRESHOLD = 10_000.0; // MYR

    // ── Entry point ────────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // ── Source: Kafka ──────────────────────────────────────────────────────
        KafkaSource<Transaction> kafkaSource = KafkaSource.<Transaction>builder()
                .setBootstrapServers(KAFKA_BROKERS)
                .setTopics(KAFKA_TOPIC)
                .setGroupId(KAFKA_GROUP)
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new TransactionDeserializer())
                .build();

        DataStream<Transaction> transactions = env.fromSource(
                kafkaSource,
                WatermarkStrategy.noWatermarks(),
                "Kafka: transactions"
        );

        // ── Fraud detection window ─────────────────────────────────────────────
        // Key by account_id so each account's transactions aggregate independently.
        // TumblingProcessingTimeWindows fires every 60 s; no late-arrival handling
        // needed for this demo (use event-time + watermarks in production).
        DataStream<FraudAlert> alerts = transactions
                .keyBy(Transaction::getAccountId)
                .window(TumblingProcessingTimeWindows.of(Time.minutes(1)))
                .aggregate(new AmountAggregator(), new AlertMapper())
                .filter(alert -> {
                    boolean isFraud = alert.getTotalAmount() > FRAUD_THRESHOLD;
                    if (isFraud) {
                        LOG.warn("FRAUD DETECTED: {}", alert);
                    }
                    return isFraud;
                });

        // ── Sink 1: Fraud alerts → PostgreSQL ──────────────────────────────────
        alerts.addSink(JdbcSink.sink(
                "INSERT INTO fraud_alerts " +
                "(account_id, total_amount, transaction_count, window_start, window_end, detected_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
                (stmt, alert) -> {
                    stmt.setString(   1, alert.getAccountId());
                    stmt.setDouble(   2, alert.getTotalAmount());
                    stmt.setInt(      3, alert.getTransactionCount());
                    stmt.setTimestamp(4, Timestamp.from(Instant.parse(alert.getWindowStart())));
                    stmt.setTimestamp(5, Timestamp.from(Instant.parse(alert.getWindowEnd())));
                    stmt.setTimestamp(6, Timestamp.from(Instant.parse(alert.getDetectedAt())));
                },
                JdbcExecutionOptions.builder()
                        .withBatchSize(1)          // insert immediately, no batching
                        .withMaxRetries(3)
                        .build(),
                new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl(PG_URL)
                        .withDriverName(PG_DRIVER)
                        .withUsername(PG_USER)
                        .withPassword(PG_PASSWORD)
                        .build()
        ));

        // ── Sink 2: All raw transactions → local landing zone ─────────────────
        transactions.addSink(new LandingZoneSink(LANDING_ZONE));

        env.execute("NeoBank Fraud Detector");
    }

    // ── Window functions ───────────────────────────────────────────────────────

    /**
     * Accumulator: [sum_of_amounts, transaction_count]
     */
    private static class AmountAggregator
            implements AggregateFunction<Transaction, double[], double[]> {

        @Override
        public double[] createAccumulator() {
            return new double[]{0.0, 0.0};
        }

        @Override
        public double[] add(Transaction tx, double[] acc) {
            acc[0] += tx.getAmount();
            acc[1]++;
            return acc;
        }

        @Override
        public double[] getResult(double[] acc) {
            return acc;
        }

        @Override
        public double[] merge(double[] a, double[] b) {
            return new double[]{a[0] + b[0], a[1] + b[1]};
        }
    }

    /**
     * Maps the aggregated result + window metadata into a {@link FraudAlert}.
     */
    private static class AlertMapper
            extends ProcessWindowFunction<double[], FraudAlert, String, TimeWindow> {

        @Override
        public void process(String accountId, Context ctx,
                            Iterable<double[]> elements, Collector<FraudAlert> out) {

            double[] acc    = elements.iterator().next();
            TimeWindow win  = ctx.window();

            FraudAlert alert = new FraudAlert();
            alert.setAccountId(accountId);
            alert.setTotalAmount(Math.round(acc[0] * 100.0) / 100.0);
            alert.setTransactionCount((int) acc[1]);
            alert.setWindowStart(Instant.ofEpochMilli(win.getStart()).toString());
            alert.setWindowEnd(Instant.ofEpochMilli(win.getEnd()).toString());
            alert.setDetectedAt(Instant.now().toString());

            out.collect(alert);
        }
    }
}
