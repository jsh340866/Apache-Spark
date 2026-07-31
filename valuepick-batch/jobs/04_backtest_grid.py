"""
04_backtest_grid.py - 전략 그리드 백테스트 (PROJECT_INSTRUCTIONS.md 4.4, 프로젝트 핵심)

역할: conf/strategies.yaml에 정의된 전략 조합(PER상한 x PBR상한 x 배당수익률하한 x 리밸런싱주기 x
보유종목수) 각각에 대해, 매 리밸런싱 시점마다 조건에 맞는 종목으로 포트폴리오를 구성하고 다음
리밸런싱 시점까지 보유했을 때의 수익률을 계산해 누적 성과(누적수익률/MDD/샤프비율)를 산출한다.

리밸런싱 시점: 재무제표(사업보고서)는 연 1회만 확보되어 있어 스크리닝 기준 자체를 월별/분기별로
갱신할 수는 없다. 대신 "재무 기준은 그 시점 이전 가장 최근 공시된 사업보고서를 계속 사용하되,
가격은 매월/매분기 재평가해서 포트폴리오를 재구성"하는 방식으로 monthly/quarterly 주기를 실제로
구분한다. 예: 2022년 내내는 2021년 사업보고서(rcept_no가 2022년 3월경) 기준으로 스크리닝하다가,
2023년 3월 이후부터는 2022년 사업보고서 기준으로 자동 전환된다.

룩어헤드 바이어스 방지 (필수):
  리밸런싱 시점 t에서 스크리닝에 사용하는 재무제표는 반드시 t 시점 이전에 실제로 공시된 것이어야
  한다. 03_build_indicators.py가 흘려보낸 rcept_no(DART 접수번호, 앞 8자리가 실제 공시일)로
  "rcept_no <= 리밸런싱 시점"을 조인 조건에 명시한다. 이 조건이 빠지면 미래에 공시될 재무제표로
  과거 시점 투자 판단을 하는 셈이 되어 백테스트 신뢰도가 무너진다. 같은 종목에 rcept_no <= t를
  만족하는 연도가 여럿이면(예: 2021년치와 2022년치 사업보고서가 모두 이미 공시됨) 그중 가장 최근
  공시된 것을 사용한다.

종목 선정 기준: 조건(PER/PBR/배당수익률)을 만족하는 종목이 portfolio_size보다 많으면 PER이 낮은
(더 저평가된) 순으로 상위 N종목을 선택한다. 동일 비중(1/N)으로 매수했다고 가정한다.

배당수익률 관련 알려진 제약: dividends 원본이 2023년치만 수집되어 있어 2021/2022년 dividend_yield는
전부 null이다. dividend_yield_min이 설정된 전략은 2021/2022 시점에서 조건을 평가할 수 없는 종목을
"조건 불충족(제외)"으로 처리한다 - null을 조건을 만족한 것으로 잘못 통과시키지 않기 위함.

입력:
  - conf/strategies.yaml
  - data/indicators (year 파티셔닝, rcept_no 포함)
  - data/cleaned/prices (snapshot_type=current)
출력: data/backtest_results (전략별 리밸런싱 구간 수익률 + 전략별 최종 성과 요약)
"""

from __future__ import annotations

import argparse
from datetime import date

import yaml
from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F
from pyspark.sql.window import Window

# 03_build_indicators.py의 MAX_VALID_DIVIDEND_YIELD와 동일 - 시점별 배당수익률 재계산에도
# 같은 이상치 기준(공시 오류로 비정상적으로 큰 배당수익률 제외)을 적용해야 하므로 값을 복제한다.
MAX_VALID_DIVIDEND_YIELD = 100.0


def load_strategies(spark: SparkSession, path: str) -> DataFrame:
    """conf/strategies.yaml을 읽어 Spark DataFrame으로 변환 (전략 수만큼의 작은 테이블)"""
    with open(path, "r", encoding="utf-8") as f:
        strategies = yaml.safe_load(f)["strategies"]
    return spark.createDataFrame(strategies)


def month_end(year: int, month: int) -> str:
    """해당 월의 마지막 날짜(YYYYMMDD). 실제 거래일 여부는 이후 가격 조회 단계에서
    "그 날짜 이하 가장 최근 거래일"로 보정하므로 여기서는 달력상 말일만 계산하면 된다."""
    if month == 12:
        next_month_first = date(year + 1, 1, 1)
    else:
        next_month_first = date(year, month + 1, 1)
    from datetime import timedelta
    last_day = next_month_first - timedelta(days=1)
    return last_day.strftime("%Y%m%d")


def build_rebalance_dates(years: list[str], rebalance: str) -> list[str]:
    """리밸런싱 시점 목록 생성.
    monthly: 대상 연도 전체의 매월 말. quarterly: 3/6/9/12월 말.
    재무 기준(사업보고서)은 연 1회뿐이라 이 함수는 "가격 재평가/포트폴리오 재구성 시점"만 정의하고,
    스크리닝에 쓸 재무연도는 screen_portfolio가 rcept_no로 각 시점마다 자동 판단한다."""
    year_ints = sorted(int(y) for y in years)
    months = range(1, 13) if rebalance == "monthly" else (3, 6, 9, 12)
    dates = []
    for y in year_ints:
        for m in months:
            dates.append(month_end(y, m))
    return sorted(dates)


def prices_at_dates(prices: DataFrame, rebalance_dates: list[str]) -> DataFrame:
    """각 리밸런싱 시점에 대해 종목별 "그 날짜 이하 가장 최근 거래일" 종가/상장주식수를 구한다.
    말일이 휴일이면 그 이전 거래일 값을 쓰기 위해 날짜별로 필터+윈도우 처리한다.
    listed_share_count도 함께 가져오는 이유: screen_portfolio가 그 시점 가격으로 PER/PBR/배당수익률을
    직접 재계산하려면 그 시점의 상장주식수(eps_t/bps_t의 분모)도 함께 필요하다(룩어헤드 바이어스 수정).
    윈도우 _rank==1 필터로 (stock_code, rebalance_date)당 정확히 1행만 남으므로 fan-out 없음."""
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


def latest_valid_indicators(indicators: DataFrame, rebalance_date: str) -> DataFrame:
    """리밸런싱 시점 t 이전에 실제로 공시된(rcept_no <= t) 재무제표 중, 종목별로 가장 최근 것을 채택.
    예: 2023-06-30 시점이면 2022년 사업보고서(2023년 3월 공시)는 통과하지만 2023년 사업보고서
    (2024년 3월 공시 예정)는 아직 존재하지 않는 것으로 취급된다."""
    eligible = indicators.filter(F.col("rcept_no") <= rebalance_date)
    w = Window.partitionBy("stock_code").orderBy(F.desc("rcept_no"))
    return eligible.withColumn("_rank", F.row_number().over(w)).filter(F.col("_rank") == 1).drop("_rank")


def compute_point_in_time_ratios(df: DataFrame) -> DataFrame:
    """리밸런싱 시점 t의 가격(price)/상장주식수(share_count_t)로 PER/PBR/배당수익률을 직접 계산한다.
    03_build_indicators.py의 calculate_core_ratios/join_dividend_yield가 "최신" 가격 하나로 고정
    계산해버리는 룩어헤드 바이어스를 피하기 위해, 03번이 넘겨준 재무 원본(net_income_krw/equity_krw/
    dividend_amount)에 시점별 가격을 조인해 여기서 다시 계산한다. 03번의 가드를 그대로 복제해 의미를
    보존한다 (eps>0일 때만 PER, equity_krw>0 & bps!=0일 때만 PBR, share_count_t!=0 체크,
    price!=0 & dividend_amount not null일 때만 배당수익률, 배당수익률 100% 초과는 공시 오류로 제외)."""
    df = df.withColumn(
        "eps_t",
        F.when(F.col("share_count_t") != 0, F.col("net_income_krw") / F.col("share_count_t")),
    )
    df = df.withColumn(
        "bps_t",
        F.when((F.col("equity_krw") > 0) & (F.col("share_count_t") != 0),
               F.col("equity_krw") / F.col("share_count_t")),
    )
    df = df.withColumn(
        "per_t",
        F.when(F.col("eps_t") > 0, F.col("price") / F.col("eps_t")),
    )
    df = df.withColumn(
        "pbr_t",
        F.when((F.col("equity_krw") > 0) & (F.col("bps_t") != 0), F.col("price") / F.col("bps_t")),
    )
    df = df.withColumn(
        "dividend_yield_t",
        F.when((F.col("price") != 0) & F.col("dividend_amount").isNotNull(),
               F.col("dividend_amount") / F.col("price") * 100),
    )
    df = df.withColumn(
        "dividend_yield_t",
        F.when(F.col("dividend_yield_t") <= MAX_VALID_DIVIDEND_YIELD, F.col("dividend_yield_t")),
    )
    return df


def screen_portfolio(indicators: DataFrame, prices_snapshot: DataFrame, strategies: DataFrame,
                      rebalance_date: str) -> DataFrame:
    """리밸런싱 시점 하나에 대해, 전략별로 조건을 만족하는 종목 중 PER 낮은 순 상위 portfolio_size종목 선정.
    prices_snapshot은 이미 이 rebalance_date로 필터된 (stock_code당 1행) 가격 스냅샷이어야 한다."""
    ind = latest_valid_indicators(indicators, rebalance_date)

    # 가격 조인은 crossJoin(전략 테이블) 이전에 수행 - 전략 수(1,000)만큼 행이 불어나기 전에
    # 종목 수 규모(수천 행)에서 끝내는 것이 훨씬 저렴하다. inner join: 그 시점 가격이 없는 종목은
    # 애초에 매매 불가하므로 스크리닝 대상에서 자연히 제외된다.
    ind_priced = ind.join(prices_snapshot, on="stock_code", how="inner")
    ind_priced = compute_point_in_time_ratios(ind_priced)

    # 전략 수 x 종목 수 조합 생성 (작은 전략 테이블을 브로드캐스트해 셔플 최소화)
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


def calculate_period_returns(portfolios: DataFrame, prices_by_date: DataFrame, rebalance_dates: list[str]) -> DataFrame:
    """각 전략의 리밸런싱 구간(rebalance_dates[i] 매수 -> rebalance_dates[i+1] 매도)별 포트폴리오 수익률.
    동일비중(1/portfolio_size) 가정, 구간 내 종목별 수익률의 단순평균."""
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


def summarize_performance(period_returns: DataFrame) -> DataFrame:
    """전략별 누적수익률 / MDD / 샤프비율 계산.
    구간 수익률을 시간순으로 복리 누적 -> 누적곡선의 고점 대비 낙폭(MDD) -> 평균/표준편차로 샤프비율 근사."""
    w = Window.partitionBy("name").orderBy("period_start")
    with_cum = period_returns.withColumn(
        "cum_return",
        F.exp(F.sum(F.log(F.col("period_return") + 1.0)).over(w)) - 1.0,
    )
    # 자산곡선의 시작점(투자 직후, 누적수익률 0%)도 고점 후보에 포함해야 한다. max(cum_return)만 쓰면
    # 첫 구간부터 하락한 전략의 running_peak이 그 음수값이 되어 첫 구간 낙폭이 MDD에서 통째로 빠진다
    # (예: 첫 구간 -20% -> peak=-0.2, drawdown=0으로 계산됨). greatest(..., 0.0)로 하한을 0에 건다.
    with_peak = with_cum.withColumn(
        "running_peak",
        F.greatest(F.max("cum_return").over(w.rowsBetween(Window.unboundedPreceding, 0)), F.lit(0.0)),
    )
    with_drawdown = with_peak.withColumn(
        "drawdown", (F.col("cum_return") - F.col("running_peak")) / (F.col("running_peak") + 1.0)
    )

    # 전략별 "마지막 구간"의 누적수익률을 결정적으로 뽑는다.
    # groupBy().agg(F.last(...))는 셔플 이후의 행 순서에 의존하는 비결정적 함수라, 앞에서 윈도우로
    # 정렬했더라도 마지막 구간 값이 나온다는 보장이 없다(03_build_indicators.py에서 F.first()의 같은
    # 순서 의존성 때문에 자본총계가 잘못 채택된 사례를 이미 겪었다). orderBy와 프레임을 명시한 윈도우
    # 위에서의 F.last()는 순서가 정의되어 있어 안전하므로, 마지막 행 값을 그룹 전체 행에 채운 뒤
    # 집계한다. 그룹 내에서 상수이므로 F.max()로 꺼내도 값이 달라지지 않는다.
    w_full = w.rowsBetween(Window.unboundedPreceding, Window.unboundedFollowing)
    with_final = with_drawdown.withColumn("final_cum_return", F.last("cum_return").over(w_full))

    summary = with_final.groupBy("name").agg(
        F.max("final_cum_return").alias("final_cum_return"),
        F.min("drawdown").alias("mdd"),
        F.avg("period_return").alias("avg_period_return"),
        F.stddev("period_return").alias("stddev_period_return"),
    )
    summary = summary.withColumn(
        "sharpe_ratio",
        F.when(F.col("stddev_period_return") > 0, F.col("avg_period_return") / F.col("stddev_period_return")),
    )
    return summary


def run_for_rebalance_group(indicators: DataFrame, prices: DataFrame, strategies: DataFrame,
                             years: list[str], rebalance: str) -> tuple[DataFrame, DataFrame]:
    """monthly 또는 quarterly 그룹 전략들에 대해 리밸런싱 시점 생성부터 성과 요약까지 전체 파이프라인 실행.
    시점 수(monthly 36개, quarterly 12개)만큼 screen_portfolio를 반복 호출해 union하면 DataFrame
    실행계획(lineage)이 시점 수만큼 계속 이어붙어 드라이버에서 OutOfMemoryError가 발생했다(실측 확인).
    union 직후 .cache()로 lineage를 끊어 이를 방지한다."""
    rebalance_dates = build_rebalance_dates(years, rebalance)
    print(f"[{rebalance}] 리밸런싱 시점 {len(rebalance_dates)}개: {rebalance_dates[0]} ~ {rebalance_dates[-1]}")

    group_strategies = strategies.filter(F.col("rebalance") == rebalance)
    prices_by_date = prices_at_dates(prices, rebalance_dates).cache()

    portfolio_results = []
    for rebalance_date in rebalance_dates[:-1]:
        # prices_by_date는 이미 캐시된 상태이므로 시점별 필터는 추가 스캔 없이 캐시에서 처리된다.
        # screen_portfolio 내부에서 crossJoin(전략) 이전에 이 (stock_code당 1행) 스냅샷과 조인한다.
        date_prices = prices_by_date.filter(F.col("rebalance_date") == rebalance_date)
        portfolio = screen_portfolio(indicators, date_prices, group_strategies, rebalance_date)
        portfolio_results.append(portfolio)
    portfolios = portfolio_results[0]
    for p in portfolio_results[1:]:
        portfolios = portfolios.unionByName(p)
    portfolios = portfolios.cache()
    portfolios.count()  # 캐시를 즉시 실체화해 이후 단계에서 lineage 재계산이 일어나지 않게 함

    period_returns = calculate_period_returns(portfolios, prices_by_date, rebalance_dates).cache()
    period_returns.count()  # calculate_period_returns 내부도 구간 수만큼 반복 union하므로 여기서 실체화

    summary = summarize_performance(period_returns).cache()
    summary.count()

    # prices_by_date/portfolios는 이 함수 안에서만 쓰고 끝나는 중간 산물이다. unpersist 없이 두면
    # monthly 처리분이 executor 메모리에 남은 채로 quarterly 처리가 또 캐싱을 쌓아, 실제로
    # OutOfMemoryError가 발생했다(portfolio_size를 9999->300으로 낮춰도 재발 - 근본 원인이 크기가
    # 아니라 캐시 미해제였음을 실측 확인). period_returns/summary는 main()의 최종 write까지 필요하므로
    # 여기서는 놓지 않는다.
    prices_by_date.unpersist()
    portfolios.unpersist()
    return period_returns, summary


def main():
    parser = argparse.ArgumentParser(description="04_backtest_grid: 전략 그리드 백테스트")
    parser.add_argument("--strategies-file", default="/opt/spark-apps/conf/strategies.yaml")
    parser.add_argument("--indicators-dir", default="/opt/spark-apps/data/indicators")
    parser.add_argument("--prices-dir", default="/opt/spark-apps/data/cleaned/prices")
    parser.add_argument("--output-dir", default="/opt/spark-apps/data/backtest_results")
    parser.add_argument("--years", default="2021,2022,2023", help="백테스트 대상 연도 (콤마 구분)")
    parser.add_argument("--companies-dir", default="/opt/spark-apps/data/raw/companies")
    parser.add_argument("--market", choices=["ALL", "KOSPI", "KOSDAQ"], default="ALL",
                         help="시장 구분 필터 (companies.corp_cls: Y=KOSPI, K=KOSDAQ). 기본값은 전체")
    args = parser.parse_args()

    spark = SparkSession.builder.appName("04_backtest_grid").getOrCreate()

    years = args.years.split(",")
    strategies = load_strategies(spark, args.strategies_file)
    print(f"전략 조합 로드 완료: {strategies.count()}개")

    indicators = spark.read.parquet(args.indicators_dir)
    if args.market != "ALL":
        corp_cls = "Y" if args.market == "KOSPI" else "K"
        companies = spark.read.parquet(args.companies_dir).filter(F.col("corp_cls") == corp_cls)
        market_codes = companies.select("stock_code").distinct()
        indicators = indicators.join(F.broadcast(market_codes), on="stock_code", how="inner")
        print(f"--market {args.market} 필터 적용: 대상 종목 {market_codes.count()}개")

    # monthly(36개)+quarterly(12개) 총 48개 리밸런싱 시점에서 반복 참조되므로 캐싱해 재스캔 방지
    indicators = indicators.cache()
    prices = spark.read.parquet(args.prices_dir)

    period_returns_all = []
    summary_all = []
    for rebalance in ("monthly", "quarterly"):
        period_returns, summary = run_for_rebalance_group(indicators, prices, strategies, years, rebalance)
        period_returns_all.append(period_returns)
        summary_all.append(summary)
        print(f"[{rebalance}] 구간별 수익률/성과 요약 완료")

    period_returns = period_returns_all[0].unionByName(period_returns_all[1])
    summary = summary_all[0].unionByName(summary_all[1])
    print(f"전체 전략 성과 요약 완료: {summary.count()}개 전략")

    period_returns.write.mode("overwrite").parquet(f"{args.output_dir}/period_returns")
    summary.write.mode("overwrite").parquet(f"{args.output_dir}/summary")
    print(f"backtest_results 저장 완료: {args.output_dir}")

    # monthly/quarterly 각 그룹의 period_returns/summary(union 이전 원본)와 indicators 캐시를 정리.
    # write까지 끝났으므로 더 이상 필요 없다.
    for df in period_returns_all + summary_all:
        df.unpersist()
    indicators.unpersist()

    spark.stop()


if __name__ == "__main__":
    main()
