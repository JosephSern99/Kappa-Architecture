import psycopg2
import pandas as pd
import seaborn as sns
import matplotlib.pyplot as plt

# 1. Connect to the Database provided in docker-compose
conn = psycopg2.connect(
    host="localhost",
    port=5433,
    database="bank_db",
    user="postgres",
    password="postgres"
)

# 2. Extract Data processed by Java/Flink
with conn.cursor() as cur:
    cur.execute("SELECT * FROM fraud_alerts")
    rows = cur.fetchall()
    columns = [desc[0] for desc in cur.description]

conn.close()
df = pd.DataFrame(rows, columns=columns)

# 3. Simple Analytics for the Dashboard
if not df.empty:
    sns.countplot(data=df, x='account_id')
    plt.title("Fraud Alerts by Account")
    plt.savefig("fraud_report.png")
    print("Report generated successfully using Python 3.13.12")
else:
    print("No fraud alerts found yet.")
