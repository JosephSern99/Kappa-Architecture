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
df = pd.read_sql_query("SELECT * FROM fraud_alerts", conn)

# 3. Simple Analytics for the Dashboard
if not df.empty:
    sns.countplot(data=df, x='merchant_category')
    plt.title("Fraud Distribution by Category")
    plt.savefig("fraud_report.png")
    print("Report generated successfully using Python 3.13.12")