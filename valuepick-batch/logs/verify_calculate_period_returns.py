"""calculate_period_returns 벡터화 전/후 결과가 동일한지 대조하는 일회성 검증 스크립트.

기존(구간 쌍마다 filter+join 반복 후 unionByName) 로직을 이 스크립트 안에 그대로 복제해두고,
04_backtest_grid.py의 새 구현(구간 목록 DataFrame + 조인 벡터화)과 같은 입력으로 돌려서 두 결과를
완전 대조한다. 행 수만 비교하면 부족하므로 (name, period_start, period_end) 키로 조인해
값(period_return, held_count) 차이까지 확인한다.

입력은 screen_portfolio의 새 벡터화 구현으로 만든 portfolios를 그대로 쓴다 - 이미
verify_screen_portfolio.py에서 완전 일치를 확인했으므로 이 스크립트의 대조군으로 신뢰할 수 있다.
"""
import importlib.util
import sys

from pyspark.sql import SparkSession, functions as F

spec = importlib.util.spec_from_file_location("job04", "/opt/spark-apps/jobs/04_backtest_grid.py")
job04 = importlib.util.module_from_spec(spec)
sys.modules["job04"] = job04
spec.loader.exec_module(job04)


def old_calculate_period_returns(portfolios, prices_by_date, rebalance_dates):
    """벡터화 전 원본 로직 그대로 복제 (대조군)."""
    period_results = []
    for buy_date, sell_date in zip(rebalance_dates[:-1], rebalance_dates[1:]):
        buy_prices = prices_by_date.filter(F.col("rebalance_date") == buy_date) \
            .select("stock_code", F.col("price").alias("buy_price"))
        sell_prices = prices_by_date.filter(F.col("rebalance_date") == sell_date) \
            .select("stock_code", F.col("price").alias("sell_price"))

        portfolio = portfolios.filter(F.col("rebalance_date") == buy_date)
        with_prices = portfolio.join(buy_prices, on="stock_code", how="inner") \
            .join(sell_prices, on="stock_code", how="inner")

        stock_return = (F.col("sell_price") - F.col("buy_price")) / F.col("buy_price")
        with_return = with_prices.withColumn("stock_return", stock_return)

        period_return = with_return.groupBy("name").agg(
            F.avg("stock_return").alias("period_return"),
            F.count("stock_code").alias("held_count"),
        ).withColumn("period_start", F.lit(buy_date)).withColumn("period_end", F.lit(sell_date))
        period_results.append(period_return)

    out = period_results[0]
    for r in period_results[1:]:
        out = out.unionByName(r)
    return out


spark = SparkSession.builder.appName("verify-calculate-period-returns").getOrCreate()
spark.sparkContext.setLogLevel("ERROR")

indicators = spark.read.parquet("/opt/spark-apps/data/indicators")
prices = spark.read.parquet("/opt/spark-apps/data/cleaned/prices")
strategies = job04.load_strategies(spark, "/opt/spark-apps/conf/strategies.yaml")
monthly_strategies = strategies.filter(F.col("rebalance") == "monthly")

# 구간이 최소 3개는 나오도록 시점 4개로 검증 (구간 = 시점수 - 1)
dates = job04.build_rebalance_dates(["2021", "2022", "2023"], "monthly")[:36]
print(f"검증 대상 시점 4개(구간 3개): {dates}")

prices_by_date = job04.prices_at_dates(spark, prices, dates).cache()
prices_by_date.count()

buy_dates = dates[:-1]
buy_prices_by_date = prices_by_date.filter(F.col("rebalance_date").isin(buy_dates))
portfolios = job04.screen_portfolio(
    spark, indicators, buy_prices_by_date, monthly_strategies, buy_dates
).cache()
portfolios.count()

old = old_calculate_period_returns(portfolios, prices_by_date, dates).cache()
new = job04.calculate_period_returns(spark, portfolios, prices_by_date, dates).cache()

old_count = old.count()
new_count = new.count()
print(f"행 수 — 기존: {old_count}  신규: {new_count}  {'일치' if old_count == new_count else '불일치'}")

diff = old.alias("o").join(
    new.alias("n"),
    on=["name", "period_start", "period_end"],
    how="full_outer",
).filter(
    F.col("o.period_return").isNull() | F.col("n.period_return").isNull()
    | (F.abs(F.col("o.period_return") - F.col("n.period_return")) > 1e-9)
    | (F.col("o.held_count") != F.col("n.held_count"))
)
diff_count = diff.count()
print(f"불일치/누락 행: {diff_count}건 {'(완전 일치)' if diff_count == 0 else '(문제 있음, 아래 샘플)'}")
if diff_count > 0:
    diff.show(20, truncate=False)

spark.stop()
