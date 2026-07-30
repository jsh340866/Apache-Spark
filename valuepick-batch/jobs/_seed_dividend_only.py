"""_seed_dividend_only.py - _seed_valuepick_db.py의 DIVIDEND_INFO 부분만 재실행 (검증 전용, 1회성)"""
from pyspark.sql import SparkSession
from pyspark.sql import functions as F

MYSQL_URL = "jdbc:mysql://db:3306/valuepick?serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
MYSQL_PROPS = {"user": "valuepick", "password": "1234", "driver": "com.mysql.cj.jdbc.Driver"}

spark = SparkSession.builder.appName("_seed_dividend_only").getOrCreate()

dividends = spark.read.parquet("/opt/spark-apps/data/raw/dividends")
div_common = dividends.filter(
    (F.col("se") == "주당 현금배당금(원)") & (F.col("stock_knd") == "보통주")
)
dividend_rows = div_common.select(
    F.col("corp_code"),
    F.lit("보통주").alias("dividend_kind"),
    F.regexp_replace(F.trim(F.col("thstrm")), ",", "").cast("long").alias("dividend_amount"),
    F.to_timestamp(F.col("stlm_dt")).alias("stlm_dt"),
).dropDuplicates(["corp_code", "dividend_kind"])

dividend_rows.write.jdbc(url=MYSQL_URL, table="DIVIDEND_INFO", mode="append", properties=MYSQL_PROPS)
print(f"DIVIDEND_INFO 적재 완료: {dividend_rows.count()}건")
spark.stop()
