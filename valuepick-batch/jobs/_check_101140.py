from pyspark.sql import SparkSession
from pyspark.sql import functions as F

spark = SparkSession.builder.appName("check_101140").getOrCreate()

prices = spark.read.parquet("/opt/spark-apps/data/cleaned/prices") \
    .filter((F.col("stock_code") == "101140") & (F.col("snapshot_type") == "current")) \
    .filter((F.col("bas_dt") >= "20230920") & (F.col("bas_dt") <= "20231105"))

prices.select("bas_dt", "close_price", "fluctuation_rate", "is_interpolated", "split_suspected") \
    .orderBy("bas_dt").show(40, truncate=False)

companies = spark.read.parquet("/opt/spark-apps/data/raw/companies")
row = companies.filter(F.col("stock_code") == "101140").select("corp_name").first()
print("종목명:", row)

spark.stop()
