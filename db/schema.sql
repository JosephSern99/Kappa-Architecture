-- Run once against the bank_db database after starting docker-compose.
-- docker exec -i postgres-db psql -U postgres -d bank_db < db/schema.sql
-- DBeaver / psql: connect to localhost:5433 (Docker), not 5432 (Laragon)

CREATE TABLE IF NOT EXISTS fraud_alerts (
    id                SERIAL PRIMARY KEY,
    account_id        VARCHAR(20)    NOT NULL,
    total_amount      NUMERIC(12, 2) NOT NULL,
    transaction_count INT            NOT NULL,
    window_start      TIMESTAMP      NOT NULL,
    window_end        TIMESTAMP      NOT NULL,
    detected_at       TIMESTAMP      NOT NULL DEFAULT NOW()
);

-- Index for the analytics dashboard query (filter by current hour)
CREATE INDEX IF NOT EXISTS idx_fraud_alerts_detected_at ON fraud_alerts (detected_at);
