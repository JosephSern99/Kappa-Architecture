# Project: NeoBank-Guard (Polyglot Data Engineering)

## 1. Project Goal
Build a real-time transaction monitoring system. Use Java for high-performance ingestion and Flink processing, and Python 3.13 for post-processing analytics and AWS S3 management.

## 2. The Java Phase (The "Core")
- **Technology:** Java 17, Maven, Flink 1.20.
- **Task 1:** Create a `TransactionProducer.java` that sends JSON to Kafka.
- **Task 2:** Create a `FraudDetectorJob.java` (Flink) that:
    - Calculates a 1-minute tumbling window per `account_id`.
    - If `sum(amount)` > 10,000 MYR, write an alert to Postgres `fraud_alerts` table.
    - Sink every raw transaction to a local file directory (simulating a landing zone).

## 3. The Python Phase (The "Intelligence" - Python 3.13.12)
- **Technology:** Python 3.13.12, Pandas, Boto3 (AWS SDK).
- **Task 3:** Create `aws_sync.py` to:
    - Monitor the local landing zone.
    - Convert JSON logs to **Parquet** format.
    - Upload Parquet files to **LocalStack S3** (bucket: `bank-data-lake`).
- **Task 4:** Create `analytics_dashboard.py` to:
    - Query Postgres for the latest fraud alerts.
    - Print a summary of "Total At-Risk Volume" for the current hour.

## 4. Why this matters (Job Market)
This demonstrates the ability to build a **production-grade pipeline**:
1. Java handles the "Stream" (High throughput).
2. Python handles the "Lake" and "Analytics" (High flexibility).
3. AWS (via LocalStack) demonstrates cloud readiness for MSK/S3/RDS.