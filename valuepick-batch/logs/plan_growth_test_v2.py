"""screen_portfolio 벡터화 이후 계획 트리 크기가 시점 수와 무관해졌는지 재측정하는 스크립트.

plan_growth_test.py(벡터화 전 버전 측정)와 같은 패턴: 리밸런싱 시점 수만 1/3/6/12로 바꿔가며
"액션 없이" 계획 문자열 길이만 잰다. 벡터화 전에는 시점 1개 32,218자 -> 12개 1,010,833자로
초선형 증가였다. 이번에는 screen_portfolio에 시점 목록 전체를 한 번에 넘기므로, 계획 조각 수가
시점 수와 무관하게 1개로 고정되어 계획 길이도 거의 늘지 않아야 한다.
"""
import importlib.util
import sys
import time

from pyspark.sql import SparkSession, functions as F

spec = importlib.util.spec_from_file_location("job04", "/opt/spark-apps/jobs/04_backtest_grid.py")
job04 = importlib.util.module_from_spec(spec)
sys.modules["job04"] = job04
spec.loader.exec_module(job04)

spark = SparkSession.builder.appName("plan-growth-test-v2").getOrCreate()
spark.sparkContext.setLogLevel("ERROR")

indicators = spark.read.parquet("/opt/spark-apps/data/indicators")
prices = spark.read.parquet("/opt/spark-apps/data/cleaned/prices")
strategies = job04.load_strategies(spark, "/opt/spark-apps/conf/strategies.yaml")
monthly = strategies.filter(F.col("rebalance") == "monthly")

all_dates = job04.build_rebalance_dates(["2021", "2022", "2023"], "monthly")

print(f"{'시점수':>6} {'계획생성(초)':>12} {'계획길이(자)':>14} {'시점당길이':>12}")
print("-" * 50)

for n in [1, 3, 6, 12]:
    dates = all_dates[:n]
    t0 = time.time()

    prices_by_date = job04.prices_at_dates(spark, prices, dates)
    out = job04.screen_portfolio(spark, indicators, prices_by_date, monthly, dates)

    # 여기까지 전부 lazy - 액션이 없으므로 데이터는 읽히지 않았다.
    plan = out._jdf.queryExecution().toString()
    elapsed = time.time() - t0

    print(f"{n:>6} {elapsed:>12.2f} {len(plan):>14,} {len(plan)//n:>12,}")

spark.stop()
