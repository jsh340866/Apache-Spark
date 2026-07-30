from pyspark.sql import SparkSession
from pyspark.sql import functions as F

spark = SparkSession.builder.appName("years_check").getOrCreate()
ind = spark.read.parquet("/opt/spark-apps/data/indicators")
ind.groupBy("year").count().orderBy("year").show()

fin = spark.read.parquet("/opt/spark-apps/data/raw/financials")
print("financials bsns_year distinct:", [r[0] for r in fin.select("bsns_year").distinct().collect()])

prices = spark.read.parquet("/opt/spark-apps/data/raw/prices")
prices.groupBy("year", "snapshot_type").count().orderBy("year").show()
spark.stop()
