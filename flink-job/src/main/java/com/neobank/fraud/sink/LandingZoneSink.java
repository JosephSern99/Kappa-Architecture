package com.neobank.fraud.sink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.fraud.model.Transaction;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Appends every raw transaction as a JSON line to a per-subtask file inside
 * the landing zone directory.  Each subtask writes its own file to avoid
 * concurrent write conflicts.
 *
 * The Python aws_sync.py script monitors this directory and converts the
 * JSONL files to Parquet before uploading to LocalStack S3.
 */
public class LandingZoneSink extends RichSinkFunction<Transaction> {

    private static final Logger LOG = LoggerFactory.getLogger(LandingZoneSink.class);

    private final String directory;

    private transient ObjectMapper mapper;
    private transient BufferedWriter writer;

    public LandingZoneSink(String directory) {
        this.directory = directory;
    }

    @Override
    public void open(Configuration parameters) throws IOException {
        mapper = new ObjectMapper();

        Path dir = Paths.get(directory);
        Files.createDirectories(dir);

        // One file per Flink subtask (parallelism-safe)
        String filename = "transactions_" + getRuntimeContext().getIndexOfThisSubtask() + ".jsonl";
        Path file = dir.resolve(filename);

        writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        LOG.info("LandingZoneSink writing to: {}", file.toAbsolutePath());
    }

    @Override
    public void invoke(Transaction tx, Context context) throws IOException {
        writer.write(mapper.writeValueAsString(tx));
        writer.newLine();
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        if (writer != null) {
            writer.close();
        }
    }
}
