package com.neobank.fraud.serde;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.fraud.model.Transaction;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;

import java.io.IOException;

/**
 * Converts raw Kafka bytes (UTF-8 JSON) into {@link Transaction} POJOs.
 * ObjectMapper is initialised lazily on open() so it is not serialised as job state.
 */
public class TransactionDeserializer implements DeserializationSchema<Transaction> {

    private transient ObjectMapper mapper;

    @Override
    public void open(InitializationContext context) {
        mapper = new ObjectMapper();
    }

    @Override
    public Transaction deserialize(byte[] message) throws IOException {
        return mapper.readValue(message, Transaction.class);
    }

    @Override
    public boolean isEndOfStream(Transaction nextElement) {
        return false;
    }

    @Override
    public TypeInformation<Transaction> getProducedType() {
        return TypeInformation.of(new TypeHint<Transaction>() {});
    }
}
