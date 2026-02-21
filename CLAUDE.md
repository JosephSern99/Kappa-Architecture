# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project: NeoBank-Guard

A real-time transaction monitoring system for fraud detection. The architecture is **polyglot**: Java handles high-throughput stream ingestion and Flink processing; Python handles post-processing analytics and AWS S3 data lake management. LocalStack emulates AWS services locally.

## Infrastructure Commands

Start all services (Kafka, Flink, PostgreSQL, LocalStack):
```bash
docker-compose up -d
```

Stop all services:
```bash
docker-compose down
```

Service endpoints:
- **Kafka**: `localhost:9092` (KRaft mode, no Zookeeper)
- **Flink UI**: `http://localhost:8081`
- **PostgreSQL**: `localhost:5433` — db: `bank_db`, user: `postgres`, password: `postgres` (mapped to 5433 to avoid conflict with Laragon's PostgreSQL on 5432)
- **LocalStack S3**: `http://localhost:4566` — region: `ap-southeast-1`, bucket: `bank-data-lake`

## Java Phase (Stream Core)

**Stack:** Java 17, Maven, Apache Flink 1.20, Apache Kafka

Files to implement (not yet created):
- `TransactionProducer.java` — generates JSON transactions and publishes to Kafka
- `FraudDetectorJob.java` — Flink job that:
  - Consumes transactions from Kafka
  - Applies a 1-minute tumbling window grouped by `account_id`
  - Writes an alert to PostgreSQL `fraud_alerts` table when `sum(amount) > 10,000 MYR`
  - Sinks raw transactions to a local landing zone directory

Build and run (once `pom.xml` exists):
```bash
mvn clean package
# Submit JAR to Flink via http://localhost:8081 or flink run
```

## Python Phase (Analytics & Lake)

**Stack:** Python 3.13.12, Pandas, Boto3, psycopg2, Seaborn/Matplotlib

Files to implement:
- `aws_sync.py` — monitors the landing zone, converts JSON logs to Parquet, uploads to LocalStack S3
- `analytics_dashboard.py` — queries PostgreSQL `fraud_alerts`, prints "Total At-Risk Volume" summary
- `script.py` — skeleton exists; connects to PostgreSQL and generates `fraud_report.png` using Seaborn

LocalStack requires no real AWS credentials — use dummy values:
```bash
AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test aws --endpoint-url=http://localhost:4566 s3 ls
```

Install dependencies (once `requirements.txt` exists):
```bash
pip install -r requirements.txt
```

## Data Flow

```
TransactionProducer.java
    └─▶ Kafka (topic: transactions)
            └─▶ FraudDetectorJob.java (Flink)
                    ├─▶ PostgreSQL: fraud_alerts table
                    └─▶ Local landing zone (JSON files)
                                └─▶ aws_sync.py
                                        └─▶ LocalStack S3: bank-data-lake (Parquet)

analytics_dashboard.py ─▶ PostgreSQL (reads fraud_alerts)
```

## PostgreSQL Schema

The `fraud_alerts` table must be created before the Flink job runs. Schema to be defined, but at minimum needs: `account_id`, `amount_sum`, `window_start`, `window_end`, `merchant_category`.
