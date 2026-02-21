# NeoBank-Guard

A real-time transaction monitoring system for fraud detection, built as a polyglot data engineering pipeline. Java handles high-throughput stream ingestion and Flink processing; Python handles post-processing analytics and data lake management via AWS S3 (emulated locally with LocalStack).

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        JAVA PHASE (Stream)                      │
│                                                                 │
│  producer/                     flink-job/                       │
│  TransactionProducerService     FraudDetectorJob                │
│  (@Scheduled, Spring Kafka)     (Apache Flink 1.20)             │
│         │                              │                        │
│         │ JSON → Kafka topic           │ Kafka → keyBy(account) │
│         │   "transactions"             │ → 1-min tumbling window│
│         └──────────────────────────►  │ → sum(amount)          │
│                                        │                        │
│                              ┌─────────┴──────────┐            │
│                              │                    │            │
│                         PostgreSQL          /tmp/landing-zone  │
│                         fraud_alerts        (*.jsonl files)    │
└─────────────────────────────────────────────────────────────────┘
                                                    │
┌───────────────────────────────────────────────────▼─────────────┐
│                       PYTHON PHASE (Lake & Analytics)           │
│                                                                 │
│  aws_sync.py                      analytics_dashboard.py        │
│  Polls landing zone               Queries PostgreSQL            │
│  JSONL → Parquet → S3             Prints At-Risk Volume         │
│                                                                 │
│              LocalStack S3 (bank-data-lake)                     │
│              transactions/YYYY/MM/DD/HH/*.parquet               │
└─────────────────────────────────────────────────────────────────┘
```

### Fraud Detection Logic

The Flink job groups transactions by `account_id` in a **1-minute tumbling window**. If the sum of transaction amounts in that window exceeds **10,000 MYR**, a `FraudAlert` is written to PostgreSQL.

---

## Project Structure

```
neobank-guard/
├── pom.xml                        # Parent Maven (multi-module)
├── producer/                      # Spring Boot module
│   ├── pom.xml
│   └── src/main/java/com/neobank/producer/
│       ├── ProducerApplication.java
│       ├── TransactionProducerService.java   # @Scheduled Kafka publisher
│       ├── model/Transaction.java
│       └── resources/application.yml
├── flink-job/                     # Flink fat-JAR module
│   ├── pom.xml
│   └── src/main/java/com/neobank/fraud/
│       ├── FraudDetectorJob.java             # Main Flink job
│       ├── model/{Transaction,FraudAlert}.java
│       ├── serde/TransactionDeserializer.java
│       └── sink/LandingZoneSink.java
├── aws_sync.py                    # Landing zone → LocalStack S3
├── analytics_dashboard.py         # PostgreSQL fraud summary
├── script.py                      # Chart generator (Seaborn)
├── requirements.txt               # Python dependencies
├── db/schema.sql                  # PostgreSQL DDL
└── docker-compose.yml             # All infrastructure
```

---

## Infrastructure

| Service | Image | Port | Purpose |
|---------|-------|------|---------|
| Kafka | apache/kafka (KRaft) | 9092 | Transaction event stream |
| Flink JobManager | apache/flink:1.20-java17 | 8081 | Orchestrates Flink jobs |
| Flink TaskManager | apache/flink:1.20-java17 | — | Executes Flink tasks (2 slots) |
| PostgreSQL | postgres:16-alpine | 5433 | Persists fraud alerts (5433 avoids conflict with Laragon) |
| LocalStack | localstack/localstack | 4566 | Emulates AWS S3 |

**Credentials:** PostgreSQL — user: `postgres`, password: `postgres`, db: `bank_db`

---

## How to Run

### 1. Start infrastructure

```bash
docker-compose up -d
```

Verify all containers are healthy:

```bash
docker ps
```

### 2. Create the database schema

Run once after the postgres container is up:

```bash
docker exec -i postgres-db psql -U postgres -d bank_db < db/schema.sql
```

### 3. Build the Java modules

```bash
mvn clean package
```

This produces two JARs:
- `producer/target/producer-1.0-SNAPSHOT.jar`
- `flink-job/target/flink-job-1.0-SNAPSHOT.jar`

### 4. Start the transaction producer

```bash
java -jar producer/target/producer-1.0-SNAPSHOT.jar
```

The producer publishes ~1 transaction/second to the `transactions` Kafka topic. You should see log lines like:

```
Published: Transaction{id=..., account=ACC-003, amount=7421.50 MYR, category=retail}
```

### 5. Submit the Flink fraud detection job

Option A — via Flink UI (`http://localhost:8081`):
- Go to **Submit New Job** → upload `flink-job/target/flink-job-1.0-SNAPSHOT.jar`
- Set entry class: `com.neobank.fraud.FraudDetectorJob`
- Click **Submit**

Option B — via CLI (requires Flink installed locally):

```bash
flink run -c com.neobank.fraud.FraudDetectorJob \
  flink-job/target/flink-job-1.0-SNAPSHOT.jar
```

The job will:
- Consume from Kafka
- Write fraud alerts to PostgreSQL after every 1-minute window where an account exceeds 10,000 MYR
- Write all raw transactions to `/tmp/landing-zone/*.jsonl`

### 6. Install Python dependencies

```bash
pip install -r requirements.txt
```

### 7. Run the S3 sync

In a separate terminal, start the landing zone watcher:

```bash
python aws_sync.py
```

It polls `/tmp/landing-zone` every 30 seconds, converts idle JSONL files to Parquet, and uploads them to LocalStack S3 under `s3://bank-data-lake/transactions/YYYY/MM/DD/HH/`.

Verify uploads:

```bash
AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
  aws --endpoint-url=http://localhost:4566 s3 ls s3://bank-data-lake/ --recursive
```

### 8. View the fraud dashboard

```bash
python analytics_dashboard.py
```

Example output:

```
============================================================
  NeoBank-Guard — Fraud Dashboard
  Reporting window: 2026-02-21 10:00 UTC
============================================================
  Total At-Risk Volume : MYR     45,320.75
  Total Alerts Fired   : 6
  Accounts Flagged     : 3
------------------------------------------------------------
  Account       Alerts    At-Risk (MYR)  Transactions  Last Seen
  ------------  ------  ---------------  ------------  -------------------
  ACC-001            2  MYR   18,450.00            12  2026-02-21 10:03:00
  ACC-003            3  MYR   16,870.75             9  2026-02-21 10:05:00
  ACC-005            1  MYR   10,000.00             4  2026-02-21 10:01:00
============================================================
```

---

## Technology Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Stream ingestion | Spring Boot + Spring Kafka | 3.3.0 |
| Stream processing | Apache Flink | 1.20 |
| Message broker | Apache Kafka (KRaft) | latest |
| Database | PostgreSQL | 16 |
| Data lake | LocalStack S3 | latest |
| Analytics | Python, Pandas, pyarrow | 3.13.12 |
| Cloud SDK | Boto3 | ≥1.34 |
