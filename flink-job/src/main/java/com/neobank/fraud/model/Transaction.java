package com.neobank.fraud.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

/**
 * Mirrors the JSON schema produced by the Spring Boot producer.
 * {@code @JsonIgnoreProperties} makes deserialization tolerant of new producer fields.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Transaction implements Serializable {

    @JsonProperty("transaction_id")
    private String transactionId;

    @JsonProperty("account_id")
    private String accountId;

    private double amount;
    private String currency;

    @JsonProperty("merchant_category")
    private String merchantCategory;

    private String timestamp;

    public Transaction() {}

    public String getTransactionId()    { return transactionId; }
    public String getAccountId()        { return accountId; }
    public double getAmount()           { return amount; }
    public String getCurrency()         { return currency; }
    public String getMerchantCategory() { return merchantCategory; }
    public String getTimestamp()        { return timestamp; }

    public void setTransactionId(String transactionId)       { this.transactionId = transactionId; }
    public void setAccountId(String accountId)               { this.accountId = accountId; }
    public void setAmount(double amount)                     { this.amount = amount; }
    public void setCurrency(String currency)                 { this.currency = currency; }
    public void setMerchantCategory(String merchantCategory) { this.merchantCategory = merchantCategory; }
    public void setTimestamp(String timestamp)               { this.timestamp = timestamp; }

    @Override
    public String toString() {
        return "Transaction{id=" + transactionId + ", account=" + accountId +
               ", amount=" + amount + " " + currency + "}";
    }
}
