package com.neobank.producer;

import com.neobank.producer.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Random;

/**
 * Publishes one random Transaction to Kafka every second.
 * Amounts occasionally spike above 10,000 MYR to trigger the Flink fraud window.
 */
@Service
public class TransactionProducerService {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionProducerService.class);

    private static final String TOPIC = "transactions";

    private static final String[] ACCOUNT_IDS = {
        "ACC-001", "ACC-002", "ACC-003", "ACC-004", "ACC-005"
    };

    private static final String[] MERCHANT_CATEGORIES = {
        "retail", "food_beverage", "travel", "electronics", "utilities"
    };

    private final KafkaTemplate<String, Transaction> kafkaTemplate;
    private final Random random = new Random();

    public TransactionProducerService(KafkaTemplate<String, Transaction> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedRateString = "${producer.interval-ms:1000}")
    public void publishTransaction() {
        String accountId = ACCOUNT_IDS[random.nextInt(ACCOUNT_IDS.length)];
        String category  = MERCHANT_CATEGORIES[random.nextInt(MERCHANT_CATEGORIES.length)];

        // Normal range: 500–5,000 MYR; occasional spike to 5,000–15,000 MYR
        double amount = random.nextBoolean()
            ? 500  + random.nextDouble() * 4_500
            : 5000 + random.nextDouble() * 10_000;

        Transaction tx = new Transaction(accountId, amount, "MYR", category);

        // Key by account_id so Flink's keyed window groups correctly
        kafkaTemplate.send(TOPIC, accountId, tx);
        LOG.info("Published: {}", tx);
    }
}
