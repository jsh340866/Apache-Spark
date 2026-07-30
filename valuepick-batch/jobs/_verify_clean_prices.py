"""
_verify_clean_prices.py - 02_clean_prices.py 결과 확인용 (검증 전용, 파이프라인 구성요소 아님)

실행: spark-submit --properties-file /opt/spark-apps/conf/spark-defaults.conf \
      /opt/spark-apps/jobs/_verify_clean_prices.py
"""

from pyspark.sql import SparkSession
from pyspark.sql import functions as F
from pyspark.sql.window import Window

spark = SparkSession.builder.appName("verify_clean_prices").getOrCreate()

df = spark.read.parquet("/opt/spark-apps/data/cleaned/prices")
total = df.count()
print(f"=== 전체 {total}건 ===\n")

# ── 1. 보간된 행 (is_interpolated=true) ──────────────────────────────
interpolated = df.filter(F.col("is_interpolated"))
print(f"=== 보간된 행: {interpolated.count()}건 ===")
interpolated.select("stock_code", "bas_dt", "close_price", "is_interpolated") \
    .orderBy("stock_code", "bas_dt") \
    .show(50, truncate=False)

# ── 2. 액면분할/병합 의심 행 (split_suspected=true) ──────────────────
w = Window.partitionBy("stock_code").orderBy("bas_dt")
suspects = df.withColumn("prev_close", F.lag("close_price").over(w)) \
    .filter(F.col("split_suspected")) \
    .withColumn("day_over_day_rate", F.round((F.col("close_price") - F.col("prev_close")) / F.col("prev_close") * 100, 2))

print(f"=== 액면분할/병합 의심 행: {suspects.count()}건 ===")
suspects.select("stock_code", "bas_dt", "prev_close", "close_price", "day_over_day_rate") \
    .orderBy("stock_code", "bas_dt") \
    .show(50, truncate=False)

# ── 3. 정제 후에도 남아있는 결측치 (컬럼별 null 개수) ─────────────────
print("=== 정제 후 컬럼별 결측치 개수 ===")
null_counts = df.select([
    F.sum(F.col(c).isNull().cast("int")).alias(c) for c in df.columns
])
null_counts.show(truncate=False)

# ── 4. 종목별 데이터 커버리지 (보간 건수 상위 10) ──────────────────────
print("=== 종목별 보간 건수 상위 10 ===")
df.groupBy("stock_code") \
    .agg(F.sum(F.col("is_interpolated").cast("int")).alias("interpolated_count")) \
    .orderBy(F.desc("interpolated_count")) \
    .show(10)

spark.stop()
