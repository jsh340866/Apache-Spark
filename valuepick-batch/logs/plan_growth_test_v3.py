"""calculate_period_returns까지 벡터화된 전체 파이프라인(prices_at_dates -> screen_portfolio ->
calculate_period_returns)의 계획 트리 크기가 시점 수와 무관해졌는지 재측정하는 스크립트.

plan_growth_test.py/plan_growth_test_v2.py와 같은 패턴. 캐시를 걸지 않은 순수 lazy plan 상태로
계획 문자열 길이만 측정한다(04_backtest_grid.py 실제 실행 경로는 cache()로 lineage를 끊지만,
여기서는 "만약 캐시가 없다면 계획이 얼마나 자라는가"를 보기 위해 의도적으로 캐시를 걸지 않는다).
"""
import importlib.util
import sys
import time

from pyspark.sql import SparkSession, functions as F

spec = importlib.util.spec_from_file_location("job04", "/opt/spark-apps/jobs/04_backtest_grid.py")
job04 = importlib.util.module_from_spec(spec)
sys.modules["job04"] = job04
spec.loader.exec_module(job04)

spark = SparkSession.builder.appName("plan-growth-test-v3").getOrCreate()
spark.sparkContext.setLogLevel("ERROR")

indicators = spark.read.parquet("/opt/spark-apps/data/indicators")
prices = spark.read.parquet("/opt/spark-apps/data/cleaned/prices")
strategies = job04.load_strategies(spark, "/opt/spark-apps/conf/strategies.yaml")
monthly = strategies.filter(F.col("rebalance") == "monthly")

all_dates = job04.build_rebalance_dates(["2021", "2022", "2023"], "monthly")

print(f"{'시점수':>6} {'계획생성(초)':>12} {'계획길이(자)':>14} {'시점당길이':>12}")
print("-" * 50)

for n in [2, 4, 7, 13]:
    dates = all_dates[:n]
    t0 = time.time()

    prices_by_date = job04.prices_at_dates(spark, prices, dates)
    buy_dates = dates[:-1]
    buy_prices_by_date = prices_by_date.filter(F.col("rebalance_date").isin(buy_dates))
    portfolios = job04.screen_portfolio(spark, indicators, buy_prices_by_date, monthly, buy_dates)
    period_returns = job04.calculate_period_returns(spark, portfolios, prices_by_date, dates)

    plan = period_returns._jdf.queryExecution().toString()
    elapsed = time.time() - t0

    # 구간 수(n-1) 기준으로 나눠야 prices_at_dates_v2와 비교 단위가 맞다
    periods = max(n - 1, 1)
    print(f"{n:>6} {elapsed:>12.2f} {len(plan):>14,} {len(plan)//periods:>12,}")

spark.stop()
