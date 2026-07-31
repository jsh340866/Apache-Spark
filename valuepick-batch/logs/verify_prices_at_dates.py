"""prices_at_dates 벡터화 전/후 결과가 동일한지 대조하는 일회성 검증 스크립트.

기존(for+unionByName) 로직을 이 스크립트 안에 그대로 복제해두고, 04_backtest_grid.py의
새 구현(crossJoin 벡터화)과 같은 입력으로 돌려서 두 결과를 완전 대조한다.
행 수만 비교하면 부족하므로 (stock_code, rebalance_date) 키로 조인해 값 차이까지 확인한다.
"""
import importlib.util
import sys

from pyspark.sql import SparkSession, Window, functions as F

spec = importlib.util.spec_from_file_location("job04", "/opt/spark-apps/jobs/04_backtest_grid.py")
job04 = importlib.util.module_from_spec(spec)
sys.modules["job04"] = job04
spec.loader.exec_module(job04)


def old_prices_at_dates(prices, rebalance_dates):
    """벡터화 전 원본 로직 그대로 복제 (대조군)."""
    current = prices.filter(F.col("snapshot_type") == "current")
    results = []
    for rebalance_date in rebalance_dates:
        w = Window.partitionBy("stock_code").orderBy(F.desc("bas_dt"))
        snapshot = current.filter(F.col("bas_dt") <= rebalance_date) \
            .withColumn("_rank", F.row_number().over(w)) \
            .filter(F.col("_rank") == 1) \
            .select("stock_code", F.col("close_price").alias("price"),
                    F.col("listed_share_count").alias("share_count_t"),
                    F.lit(rebalance_date).alias("rebalance_date"))
        results.append(snapshot)
    out = results[0]
    for r in results[1:]:
        out = out.unionByName(r)
    return out


spark = SparkSession.builder.appName("verify-prices-at-dates").getOrCreate()
spark.sparkContext.setLogLevel("ERROR")

prices = spark.read.parquet("/opt/spark-apps/data/cleaned/prices")
dates = job04.build_rebalance_dates(["2021", "2022", "2023"], "monthly")[:3]
print(f"검증 대상 시점 3개: {dates}")

old = old_prices_at_dates(prices, dates).cache()
new = job04.prices_at_dates(spark, prices, dates).cache()

old_count = old.count()
new_count = new.count()
print(f"행 수 — 기존: {old_count}  신규: {new_count}  {'일치' if old_count == new_count else '불일치'}")

# 완전 대조: 두 결과를 outer join해서 값이 다른 행, 한쪽에만 있는 행을 전부 찾는다.
diff = old.alias("o").join(
    new.alias("n"),
    on=["stock_code", "rebalance_date"],
    how="full_outer",
).filter(
    F.col("o.price").isNull() | F.col("n.price").isNull()
    | (F.col("o.price") != F.col("n.price"))
    | (F.col("o.share_count_t") != F.col("n.share_count_t"))
)
diff_count = diff.count()
print(f"불일치/누락 행: {diff_count}건 {'(완전 일치)' if diff_count == 0 else '(문제 있음, 아래 샘플)'}")
if diff_count > 0:
    diff.show(20, truncate=False)

spark.stop()
