"""calculate_period_returns의 이상치(split_suspected) 제외 로직 검증.

기존(제외 없음) 로직을 복제해 대조군으로 두고, 새 구현과 비교한다.
핵심 확인: 1) 101140이 실제로 걸러지는지 2) 관련 없는 (종목,구간)은 안 바뀌는지 3) fan-out 없는지.
"""
import importlib.util
import sys

from pyspark.sql import SparkSession, functions as F

spec = importlib.util.spec_from_file_location("job04", "/opt/spark-apps/jobs/04_backtest_grid.py")
job04 = importlib.util.module_from_spec(spec)
sys.modules["job04"] = job04
spec.loader.exec_module(job04)


def old_calculate_period_returns(spark, portfolios, prices_by_date, rebalance_dates):
    """이상치 제외 없는 원본 로직 그대로 복제 (대조군)."""
    periods = list(zip(rebalance_dates[:-1], rebalance_dates[1:]))
    periods_df = spark.createDataFrame(periods, ["period_start", "period_end"])
    buy_prices = prices_by_date.select("stock_code", F.col("price").alias("buy_price"), F.col("rebalance_date").alias("period_start"))
    sell_prices = prices_by_date.select("stock_code", F.col("price").alias("sell_price"), F.col("rebalance_date").alias("period_end"))
    with_period = portfolios.join(F.broadcast(periods_df), portfolios["rebalance_date"] == periods_df["period_start"], "inner")
    with_prices = with_period.join(buy_prices, on=["stock_code", "period_start"], how="inner") \
        .join(sell_prices, on=["stock_code", "period_end"], how="inner")
    stock_return = (F.col("sell_price") - F.col("buy_price")) / F.col("buy_price")
    with_return = with_prices.withColumn("stock_return", stock_return)
    return with_return.groupBy("name", "period_start", "period_end").agg(
        F.avg("stock_return").alias("period_return"), F.count("stock_code").alias("held_count"),
    )


spark = SparkSession.builder.appName("verify-split-exclusion").getOrCreate()
spark.sparkContext.setLogLevel("ERROR")

indicators = spark.read.parquet("/opt/spark-apps/data/indicators")
prices = spark.read.parquet("/opt/spark-apps/data/cleaned/prices")
strategies = job04.load_strategies(spark, "/opt/spark-apps/conf/strategies.yaml")
monthly_strategies = strategies.filter(F.col("rebalance") == "monthly")

split_flags = prices.filter(F.col("split_suspected") == True).select(
    "stock_code", F.col("bas_dt").alias("flag_date")
).cache()
print(f"split_flags 건수: {split_flags.count()}")
split_flags.filter(F.col("stock_code") == "101140").show()

dates = job04.build_rebalance_dates(["2021", "2022", "2023"], "monthly")
buy_dates = dates[:-1]

prices_by_date = job04.prices_at_dates(spark, prices, dates).cache()
buy_prices_by_date = prices_by_date.filter(F.col("rebalance_date").isin(buy_dates))
portfolios = job04.screen_portfolio(spark, indicators, buy_prices_by_date, monthly_strategies, buy_dates).cache()
portfolios.count()

old = old_calculate_period_returns(spark, portfolios, prices_by_date, dates).cache()
new = job04.calculate_period_returns(spark, portfolios, prices_by_date, dates, split_flags).cache()

old_count = old.count()
new_count = new.count()
print(f"행 수 — 기존: {old_count}  신규(이상치 제외 후): {new_count}")
# 주의: 종목 하나가 이상치로 제외돼도 그 (전략,구간) 그룹에 남은 종목이 하나라도 있으면
# 그룹 자체는 사라지지 않는다(held_count만 줄어듦). 그래서 행 수는 old==new가 정상이며,
# 실제 반영 여부는 아래 period_return 값 diff로 판단해야 한다 - 행 수 차이로 판단하면 오판한다.
print(f"행 수는 {'같아야 정상' if old_count == new_count else '다른 것 자체가 이상'} (그룹은 안 사라짐, 그룹 내 종목 수만 줄어듦)")

# 101140이 걸린 문제의 구간(20230930->20231031)에서 실제로 값이 달라졌는지 확인
target = "per8_pbr1.2_dynone_monthly_n10"
print(f"\n=== {target}의 2023-09~10 구간 비교 ===")
old.filter((F.col("name") == target) & (F.col("period_start") == "20230930")).show(truncate=False)
new.filter((F.col("name") == target) & (F.col("period_start") == "20230930")).show(truncate=False)

# 101140과 무관한 (전략,구간)은 값이 그대로여야 한다 - 전수 대조
diff = old.alias("o").join(new.alias("n"), on=["name", "period_start", "period_end"], how="inner") \
    .filter(F.abs(F.col("o.period_return") - F.col("n.period_return")) > 1e-9)
diff_count = diff.count()
print(f"\n값이 달라진 (전략,구간) 조합 수: {diff_count}건 (전부 101140이 포함된 구간이어야 정상)")

spark.stop()
