package com.neobank.fraud.model;

import java.io.Serializable;

/**
 * Emitted by the fraud detection window and persisted to the
 * PostgreSQL {@code fraud_alerts} table.
 */
public class FraudAlert implements Serializable {

    private String accountId;
    private double totalAmount;
    private int    transactionCount;
    private String windowStart;   // ISO-8601
    private String windowEnd;     // ISO-8601
    private String detectedAt;    // ISO-8601

    public FraudAlert() {}

    public String getAccountId()        { return accountId; }
    public double getTotalAmount()       { return totalAmount; }
    public int    getTransactionCount() { return transactionCount; }
    public String getWindowStart()      { return windowStart; }
    public String getWindowEnd()        { return windowEnd; }
    public String getDetectedAt()       { return detectedAt; }

    public void setAccountId(String accountId)               { this.accountId = accountId; }
    public void setTotalAmount(double totalAmount)           { this.totalAmount = totalAmount; }
    public void setTransactionCount(int transactionCount)    { this.transactionCount = transactionCount; }
    public void setWindowStart(String windowStart)           { this.windowStart = windowStart; }
    public void setWindowEnd(String windowEnd)               { this.windowEnd = windowEnd; }
    public void setDetectedAt(String detectedAt)             { this.detectedAt = detectedAt; }

    @Override
    public String toString() {
        return "FraudAlert{account=" + accountId + ", total=" + totalAmount +
               " MYR, txns=" + transactionCount + ", window=[" + windowStart + " → " + windowEnd + "]}";
    }
}
