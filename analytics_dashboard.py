"""
analytics_dashboard.py — Fraud Alert Dashboard

Queries the PostgreSQL fraud_alerts table and prints a summary of
Total At-Risk Volume for the current hour, broken down by account.

Run:
    python analytics_dashboard.py

Prerequisites:
    docker-compose up -d postgres
    pip install -r requirements.txt
"""

import logging
from datetime import datetime, timezone

import psycopg2
import pandas as pd

# ── Logging ────────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
log = logging.getLogger(__name__)

# ── Configuration ──────────────────────────────────────────────────────────────
DB_CONFIG = {
    "host":     "localhost",
    "port":     5433,
    "dbname":   "bank_db",
    "user":     "postgres",
    "password": "postgres",
}


# ── Query ──────────────────────────────────────────────────────────────────────
QUERY = """
    SELECT
        account_id,
        COUNT(*)                    AS alert_count,
        SUM(total_amount)           AS at_risk_volume,
        SUM(transaction_count)      AS total_transactions,
        MAX(detected_at)            AS last_detected
    FROM fraud_alerts
    WHERE detected_at >= date_trunc('hour', NOW())
    GROUP BY account_id
    ORDER BY at_risk_volume DESC;
"""


def fetch_alerts() -> pd.DataFrame:
    conn = psycopg2.connect(**DB_CONFIG)
    try:
        with conn.cursor() as cur:
            cur.execute(QUERY)
            rows = cur.fetchall()
            columns = [desc[0] for desc in cur.description]
        return pd.DataFrame(rows, columns=columns)
    finally:
        conn.close()


# ── Display ────────────────────────────────────────────────────────────────────
def print_dashboard(df: pd.DataFrame) -> None:
    now = datetime.now(timezone.utc)
    hour_label = now.strftime("%Y-%m-%d %H:00 UTC")

    print()
    print("=" * 60)
    print(f"  NeoBank-Guard — Fraud Dashboard")
    print(f"  Reporting window: {hour_label}")
    print("=" * 60)

    if df.empty:
        print("  No fraud alerts detected this hour.")
        print("=" * 60)
        return

    total_at_risk = df["at_risk_volume"].sum()
    total_alerts  = df["alert_count"].sum()

    print(f"  Total At-Risk Volume : MYR {total_at_risk:>12,.2f}")
    print(f"  Total Alerts Fired   : {total_alerts}")
    print(f"  Accounts Flagged     : {len(df)}")
    print("-" * 60)
    print(f"  {'Account':<12} {'Alerts':>6}  {'At-Risk (MYR)':>15}  {'Transactions':>12}  Last Seen")
    print(f"  {'-'*12}  {'-'*6}  {'-'*15}  {'-'*12}  {'-'*19}")

    for _, row in df.iterrows():
        last = pd.Timestamp(row["last_detected"]).strftime("%Y-%m-%d %H:%M:%S")
        print(
            f"  {row['account_id']:<12}  {int(row['alert_count']):>6}  "
            f"MYR {row['at_risk_volume']:>11,.2f}  "
            f"{int(row['total_transactions']):>12}  {last}"
        )

    print("=" * 60)
    print()


# ── Entry point ────────────────────────────────────────────────────────────────
def main() -> None:
    log.info("Connecting to PostgreSQL at %s:%s/%s", DB_CONFIG["host"], DB_CONFIG["port"], DB_CONFIG["dbname"])
    try:
        df = fetch_alerts()
        print_dashboard(df)
    except psycopg2.OperationalError as e:
        log.error("Could not connect to PostgreSQL: %s", e)
        raise


if __name__ == "__main__":
    main()
