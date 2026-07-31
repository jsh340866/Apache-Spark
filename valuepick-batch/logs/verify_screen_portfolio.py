"""screen_portfolio(+latest_valid_indicators) 벡터화 전/후 결과가 동일한지 대조하는 일회성 검증 스크립트.

기존(시점 스칼라 하나만 처리 -> for문으로 반복 호출 후 unionByName) 로직을 이 스크립트 안에 그대로
복제해두고, 04_backtest_grid.py의 새 구현(crossJoin 벡터화)과 같은 입력으로 돌려서 두 결과를
완전 대조한다. 행 수만 비교하면 부족하므로 (name, stock_code, rebalance_date) 키로 조인해
누락/추가 행까지 확인한다.
"""
import importlib.util
import sys

from pyspark.sql import SparkSession, Window, functions as F

spec = importlib.util.spec_from_file_location("job04", "/opt/spark-apps/jobs/04_backtest_grid.py")
job04 = importlib.util.module_from_spec(spec)
sys.modules["job04"] = job04
spec.loader.exec_module(job04)


def old_latest_valid_indicators(indicators, rebalance_date):
    """벡터화 전 원본 로직 + year 타이브레이커 반영 (수정된 대조군).
    rcept_no만으로 orderBy하면 동일 rcept_no의 3개년 중복 행(530개 조합)이 동순위가 되어
    비결정적으로 채택되므로, job04.latest_valid_indicators와 동일하게 year를 함께 정렬해야
    "벡터화가 값을 바꾸지 않았는지"를 제대로 대조할 수 있다."""
    eligible = indicators.filter(F.col("rcept_no") <= rebalance_date)
    w = Window.partitionBy("stock_code").orderBy(F.desc("rcept_no"), F.desc("year"))
    return eligible.withColumn("_rank", F.row_number().over(w)).filter(F.col("_rank") == 1).drop("_rank")


def old_screen_portfolio(indicators, prices_snapshot, strategies, rebalance_date):
    """벡터화 전 원본 로직 그대로 복제 (대조군). prices_snapshot은 이미 그 시점으로 필터된 값."""
    ind = old_latest_valid_indicators(indicators, rebalance_date)
    ind_priced = ind.join(prices_snapshot, on="stock_code", how="inner")
    ind_priced = job04.compute_point_in_time_ratios(ind_priced)

    joined = ind_priced.crossJoin(F.broadcast(strategies))

    condition = (
        (F.col("per_t") > 0) & (F.col("per_t") <= F.col("per_max"))
        & (F.col("pbr_t") > 0) & (F.col("pbr_t") <= F.col("pbr_max"))
        & (
            F.col("dividend_yield_min").isNull()
            | (F.col("dividend_yield_t").isNotNull() & (F.col("dividend_yield_t") >= F.col("dividend_yield_min")))
        )
    )
    eligible = joined.filter(condition)

    w = Window.partitionBy("name").orderBy(F.col("per_t").asc())
    ranked = eligible.withColumn("rank", F.row_number().over(w))
    selected = ranked.filter(F.col("rank") <= F.col("portfolio_size"))

    return selected.select("name", "stock_code", "portfolio_size") \
        .withColumn("rebalance_date", F.lit(rebalance_date))


spark = SparkSession.builder.appName("verify-screen-portfolio").getOrCreate()
spark.sparkContext.setLogLevel("ERROR")

indicators = spark.read.parquet("/opt/spark-apps/data/indicators")
prices = spark.read.parquet("/opt/spark-apps/data/cleaned/prices")
strategies = job04.load_strategies(spark, "/opt/spark-apps/conf/strategies.yaml")
monthly_strategies = strategies.filter(F.col("rebalance") == "monthly")

dates = job04.build_rebalance_dates(["2021", "2022", "2023"], "monthly")[:36]
print(f"검증 대상 시점 3개: {dates}")

prices_by_date = job04.prices_at_dates(spark, prices, dates).cache()
prices_by_date.count()

# --- 기존 방식: 시점마다 old_screen_portfolio 호출 후 unionByName ---
old_results = []
for d in dates:
    date_prices = prices_by_date.filter(F.col("rebalance_date") == d)
    old_results.append(old_screen_portfolio(indicators, date_prices, monthly_strategies, d))
old = old_results[0]
for r in old_results[1:]:
    old = old.unionByName(r)
old = old.cache()

# --- 신규 방식: screen_portfolio가 시점 목록 전체를 한 번에 처리 ---
new = job04.screen_portfolio(spark, indicators, prices_by_date, monthly_strategies, dates).cache()

old_count = old.count()
new_count = new.count()
print(f"행 수 — 기존: {old_count}  신규: {new_count}  {'일치' if old_count == new_count else '불일치'}")

# 완전 대조: (name, stock_code, rebalance_date) 키로 outer join. selected 결과에는 값 컬럼이
# portfolio_size뿐이라 이것도 같이 대조한다.
diff = old.alias("o").join(
    new.alias("n"),
    on=["name", "stock_code", "rebalance_date"],
    how="full_outer",
).filter(
    F.col("o.stock_code").isNull() | F.col("n.stock_code").isNull()
    | (F.col("o.portfolio_size") != F.col("n.portfolio_size"))
)
diff_count = diff.count()
print(f"불일치/누락 행: {diff_count}건 {'(완전 일치)' if diff_count == 0 else '(문제 있음, 아래 샘플)'}")
if diff_count > 0:
    diff.show(20, truncate=False)

# 랭킹 파티셔닝 검증: (name, rebalance_date) 조합별로 종목 수가 portfolio_size를 넘지 않는지 확인.
# rebalance_date를 파티션 키에서 빠뜨렸다면 여러 시점의 종목이 한 랭킹에 섞여 이 상한이 깨진다.
over_limit = new.groupBy("name", "rebalance_date", "portfolio_size").agg(
    F.count("stock_code").alias("selected_count")
).filter(F.col("selected_count") > F.col("portfolio_size"))
over_limit_count = over_limit.count()
print(f"portfolio_size 초과 (name, rebalance_date) 조합: {over_limit_count}건 "
      f"{'(정상)' if over_limit_count == 0 else '(랭킹 파티셔닝 버그 의심)'}")
if over_limit_count > 0:
    over_limit.show(20, truncate=False)

spark.stop()
