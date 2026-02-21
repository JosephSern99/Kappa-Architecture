"""
aws_sync.py — Landing Zone → LocalStack S3 (Parquet)

Monitors /tmp/landing-zone for JSONL files written by the Flink LandingZoneSink.
Once a file has been idle (not modified) for IDLE_SECONDS it:
  1. Reads it into a Pandas DataFrame
  2. Converts to Parquet (via pyarrow)
  3. Uploads to LocalStack S3 with a date-partitioned key
  4. Archives the source JSONL so it is not re-processed

Run:
    python aws_sync.py

Prerequisites (LocalStack must be up):
    docker-compose up -d localstack
"""

import logging
import shutil
import time
from datetime import datetime, timezone
from pathlib import Path

import boto3
import pandas as pd
from botocore.exceptions import ClientError

# ── Logging ────────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
log = logging.getLogger(__name__)

# ── Configuration ──────────────────────────────────────────────────────────────
# Host-side path — Docker mounts ./landing-zone → /tmp/landing-zone inside the TaskManager.
LANDING_ZONE  = Path("./landing-zone")
ARCHIVE_DIR   = LANDING_ZONE / "archived"

S3_BUCKET     = "bank-data-lake"
S3_ENDPOINT   = "http://localhost:4566"
AWS_REGION    = "ap-southeast-1"

POLL_INTERVAL = 30   # seconds between directory scans
IDLE_SECONDS  = 10   # a file must be idle this long before we process it


# ── S3 helpers ─────────────────────────────────────────────────────────────────
def get_s3_client():
    # LocalStack accepts any non-empty dummy credentials
    return boto3.client(
        "s3",
        endpoint_url=S3_ENDPOINT,
        aws_access_key_id="test",
        aws_secret_access_key="test",
        region_name=AWS_REGION,
    )


def ensure_bucket(s3) -> None:
    try:
        s3.head_bucket(Bucket=S3_BUCKET)
        log.info("Bucket '%s' already exists.", S3_BUCKET)
    except ClientError:
        s3.create_bucket(
            Bucket=S3_BUCKET,
            CreateBucketConfiguration={"LocationConstraint": AWS_REGION},
        )
        log.info("Created bucket '%s'.", S3_BUCKET)


# ── File helpers ───────────────────────────────────────────────────────────────
def is_idle(path: Path) -> bool:
    """Return True if the file has not been modified for at least IDLE_SECONDS."""
    return (time.time() - path.stat().st_mtime) >= IDLE_SECONDS


def build_s3_key(stem: str) -> str:
    """
    Partition by ingestion time so downstream Athena/Glue queries can prune by date.
    Pattern: transactions/YYYY/MM/DD/HH/<stem>.parquet
    """
    now = datetime.now(timezone.utc)
    return f"transactions/{now.year}/{now.month:02d}/{now.day:02d}/{now.hour:02d}/{stem}.parquet"


# ── Core processing ────────────────────────────────────────────────────────────
def process_file(s3, jsonl_path: Path) -> None:
    log.info("Processing: %s", jsonl_path.name)

    df = pd.read_json(jsonl_path, lines=True)
    if df.empty:
        log.warning("  Skipped (empty file): %s", jsonl_path.name)
        return

    log.info("  Read %d rows.", len(df))

    # Write Parquet to a sibling temp file
    parquet_path = jsonl_path.with_suffix(".parquet")
    df.to_parquet(parquet_path, index=False, engine="pyarrow")

    # Upload to LocalStack S3
    s3_key = build_s3_key(jsonl_path.stem)
    s3.upload_file(str(parquet_path), S3_BUCKET, s3_key)
    log.info("  Uploaded  → s3://%s/%s", S3_BUCKET, s3_key)

    # Remove temp parquet
    parquet_path.unlink()

    # Archive source JSONL (rename with timestamp to avoid name collisions)
    ARCHIVE_DIR.mkdir(parents=True, exist_ok=True)
    archive_name = f"{jsonl_path.stem}_{int(time.time())}.jsonl"
    shutil.move(str(jsonl_path), ARCHIVE_DIR / archive_name)
    log.info("  Archived  → archived/%s", archive_name)


# ── Main loop ──────────────────────────────────────────────────────────────────
def main() -> None:
    log.info("aws_sync.py started. Watching: %s", LANDING_ZONE)
    LANDING_ZONE.mkdir(parents=True, exist_ok=True)

    s3 = get_s3_client()
    ensure_bucket(s3)

    while True:
        for jsonl_file in sorted(LANDING_ZONE.glob("*.jsonl")):
            if is_idle(jsonl_file):
                try:
                    process_file(s3, jsonl_file)
                except Exception:
                    log.exception("  Error processing %s — will retry next cycle.", jsonl_file.name)

        time.sleep(POLL_INTERVAL)


if __name__ == "__main__":
    main()
