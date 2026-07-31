"""
04_backtest_grid.py - 전략 그리드 백테스트 (PROJECT_INSTRUCTIONS.md 4.4, 프로젝트 핵심)

역할: 기존 ValuePick(Spring Boot) Top100Service.scoreAll()과 동일한 "7팩터 백분위 가중합산 점수"
방식으로 종목을 스크리닝한다. conf/strategies.yaml에 정의된 전략 조합(리밸런싱주기 x
portfolio_size x 가중치프리셋) 각각에 대해, 매 리밸런싱 시점마다 F-Score 필터를 통과한 종목 중
점수 상위 N종목으로 포트폴리오를 구성하고 다음 리밸런싱 시점까지 보유했을 때의 수익률을 계산해
누적 성과(누적수익률/MDD/샤프비율)를 산출한다.
(2026-07-31 이전의 PER상한 x PBR상한 x 배당수익률하한 문턱값 그리드 버전은
jobs/04_backtest_grid_threshold.py로 보존)

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

종목 선정 기준 (Top100Service.scoreAll() 재현):
  1) F-Score 필터: induty_code가 없는(업종 미분류) 종목은 후보에서 제외. induty_code 앞 2자리가
     64/65/66(금융업)이면 F-Score 필터를 면제, 그 외는 f_score >= 6만 통과.
  2) 점수 계산: PER/PBR/부채비율(낮을수록 고점수)과 ROE/ROA/EPS성장률/모멘텀(높을수록 고점수)
     7개 팩터를 (전략, 리밸런싱 시점) 단위로 순위 매긴 뒤 percentileFraction((n-1-rank)/(n-1))으로
     변환하고 전략별 가중치를 곱해 합산(0~1). null은 불리한 쪽 극값으로 채워 최하위 순위 취급.
  3) 점수 상위 portfolio_size종목을 동일비중(1/N)으로 매수했다고 가정한다.
  배당수익률은 Top100Service.scoreAll()이 애초에 점수 팩터로 쓰지 않으므로 이 스크리닝에 포함하지
  않는다(compute_point_in_time_ratios가 계산은 하지만 여기서는 사용 안 함).

입력:
  - conf/strategies.yaml
  - data/indicators (year 파티셔닝, rcept_no 포함)
  - data/cleaned/prices (snapshot_type=current)
  - data/raw/companies (induty_code 포함 - F-Score 금융업 예외 판정용)
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


def prices_at_dates(spark: SparkSession, prices: DataFrame, rebalance_dates: list[str]) -> DataFrame:
    """각 리밸런싱 시점에 대해 종목별 "그 날짜 이하 가장 최근 거래일" 종가/상장주식수를 구한다.
    말일이 휴일이면 그 이전 거래일 값을 쓰기 위해 "그 날짜 이하 중 최신"으로 보정한다.
    listed_share_count도 함께 가져오는 이유: screen_portfolio가 그 시점 가격으로 PER/PBR/배당수익률을
    직접 재계산하려면 그 시점의 상장주식수(eps_t/bps_t의 분모)도 함께 필요하다(룩어헤드 바이어스 수정).

    벡터화: 이전에는 시점마다 filter+Window를 만들어 unionByName으로 이어붙였는데, 그러면 시점 수만큼
    실행계획 조각이 생겨 드라이버의 계획 트리가 초선형으로 커진다(실측: 시점 1개 32K자 -> 12개 1,010K자,
    정비례 대비 2.6배). 계획 분석은 워커가 아니라 드라이버 혼자 하는 일이라, 이 구조로는 executor에
    메모리를 더 줘도 드라이버가 먼저 OOM으로 죽는다(실측 확인). 날짜 목록 자체를 DataFrame으로 만들어
    crossJoin하면 계획 조각이 1개로 고정되어 시점 수와 무관해진다.

    윈도우를 (stock_code, rebalance_date)로 파티셔닝하므로 _rank==1 필터 후 조합당 정확히 1행 — fan-out 없음."""
    current = prices.filter(F.col("snapshot_type") == "current")

    # 날짜를 파이썬 변수가 아니라 데이터로 다루는 것이 벡터화의 핵심. 36행짜리 작은 테이블이라
    # broadcast하면 셔플 없이 각 executor에서 곧바로 조합된다.
    dates_df = spark.createDataFrame([(d,) for d in rebalance_dates], ["rebalance_date"])

    # crossJoin 직후 조건 필터로 (가격일 <= 리밸런싱일) 조합만 남긴다. Catalyst가 이 필터를
    # 조인 조건으로 밀어넣으므로 실제로 모든 조합이 물리적으로 만들어지지는 않는다.
    paired = current.crossJoin(F.broadcast(dates_df)) \
        .filter(F.col("bas_dt") <= F.col("rebalance_date"))

    w = Window.partitionBy("stock_code", "rebalance_date").orderBy(F.desc("bas_dt"))
    return paired.withColumn("_rank", F.row_number().over(w)) \
        .filter(F.col("_rank") == 1) \
        .select("stock_code", F.col("close_price").alias("price"),
                F.col("listed_share_count").alias("share_count_t"),
                "rebalance_date")


def latest_valid_indicators(spark: SparkSession, indicators: DataFrame, rebalance_dates: list[str]) -> DataFrame:
    """리밸런싱 시점마다, 그 시점 이전에 실제로 공시된(rcept_no <= t) 재무제표 중 종목별로 가장 최근
    것을 채택. 예: 2023-06-30 시점이면 2022년 사업보고서(2023년 3월 공시)는 통과하지만 2023년
    사업보고서(2024년 3월 공시 예정)는 아직 존재하지 않는 것으로 취급된다.

    벡터화: prices_at_dates와 동일한 이유로, 시점마다 filter+Window를 반복해 union하면 계획 조각이
    시점 수만큼 쌓여 드라이버가 OOM으로 죽는다(실측 확인, 04_backtest_grid.py 상단 참고). 시점 목록을
    DataFrame으로 만들어 crossJoin하고 Window를 (stock_code, rebalance_date)로 파티셔닝하면 계획
    조각이 1개로 고정된다. rcept_no <= rebalance_date 필터는 시점별로 여전히 개별 적용되므로
    룩어헤드 바이어스 방지 로직은 그대로 유지된다 - 이 필터가 크로스조인 이후에도 시점 컬럼 기준으로
    걸리는 것이 핵심이라, 시점을 한 덩어리로 처리한다고 해서 다른 시점의 미래 데이터가 섞여 들어오지
    않는다.

    orderBy에 year를 반드시 함께 둬야 한다: 사업보고서 하나(rcept_no 하나)에 당기/전기/전전기
    3개년치가 함께 응답으로 와서 indicators에 (stock_code, rcept_no) 조합이 최대 3행 중복 존재한다
    (실측 확인, 530개 조합). rcept_no만으로 orderBy하면 이 3행이 동순위가 되어 row_number()가
    셔플 후 파티션 내 행 순서에 따라 비결정적으로 하나를 채택한다 - 벡터화 전/후 결과를 36개 시점
    전체로 대조하다가 16,605건 불일치로 실제 발견됨. year를 함께 내림차순 정렬해 "그 rcept_no가
    보고하는 연도들 중 가장 최근 연도(=사업보고서 본문 대상 연도)"를 결정적으로 채택한다."""
    dates_df = spark.createDataFrame([(d,) for d in rebalance_dates], ["rebalance_date"])

    paired = indicators.crossJoin(F.broadcast(dates_df)) \
        .filter(F.col("rcept_no") <= F.col("rebalance_date"))

    w = Window.partitionBy("stock_code", "rebalance_date").orderBy(F.desc("rcept_no"), F.desc("year"))
    return paired.withColumn("_rank", F.row_number().over(w)) \
        .filter(F.col("_rank") == 1) \
        .drop("_rank")


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


def apply_fscore_filter(indicators: DataFrame, companies: DataFrame) -> DataFrame:
    """Top100Service.candidates 필터 재현: induty_code가 없는 종목은 제외, 금융업(앞 2자리
    64/65/66)은 F-Score 필터 면제, 그 외는 f_score IS NOT NULL AND f_score >= 6만 통과.
    companies는 04번 main()이 --market 필터와 무관하게 induty_code까지 셀렉트해서 넘긴다 -
    F-Score/금융업 필터는 --market 옵션과 별개로 항상 적용된다."""
    ind = indicators.join(companies.select("stock_code", "induty_code"), on="stock_code", how="inner")
    ind = ind.filter(F.col("induty_code").isNotNull())
    is_financial = F.substring(F.col("induty_code"), 1, 2).isin("64", "65", "66")
    passes_fscore = F.col("f_score").isNotNull() & (F.col("f_score") >= 6)
    return ind.filter(is_financial | passes_fscore)


def add_percentile_rank(df: DataFrame, value_col: str, rank_col: str, partition_cols: list[str],
                         ascending: bool, fill_value: float) -> DataFrame:
    """Top100Service의 ascRank/descRank + percentileFraction을 그대로 재현한다.
    - null은 불리한 쪽 극값(fill_value)으로 채워 최하위 순위를 받도록 한다
      (낮을수록 좋은 지표는 +inf, 높을수록 좋은 지표는 -inf - MAX_VALUE/-MAX_VALUE와 동일한 역할).
      이 극값은 순위를 매기는 데만 쓰이고 점수 계산 자체에는 원본 값이 들어가지 않으므로 안전하다.
    - F.row_number()는 1-based라 -1을 해서 Java의 0-based rank(ascRank/descRank)와 맞춘다.
    - ascending=True면 오름차순(가장 작은 값이 rank 0) = ascRank, False면 내림차순(가장 큰 값이
      rank 0) = descRank.
    - percentileFraction(rank, n) = n<=1이면 1.0, 아니면 (n-1-rank)/(n-1).
    - (전략, 리밸런싱 시점) 단위로 partition_cols를 지정해야 서로 다른 가중치프리셋/시점의 종목이
      한 순위표에 섞이지 않는다."""
    filled = F.coalesce(F.col(value_col), F.lit(fill_value))
    order_expr = filled.asc() if ascending else filled.desc()
    w = Window.partitionBy(*partition_cols).orderBy(order_expr)
    n_col = F.count(F.lit(1)).over(Window.partitionBy(*partition_cols))
    rank0 = F.row_number().over(w) - 1
    percentile = F.when(n_col <= 1, F.lit(1.0)).otherwise(
        (n_col - 1 - rank0).cast("double") / (n_col - 1).cast("double")
    )
    return df.withColumn(rank_col, percentile)


def screen_portfolio(spark: SparkSession, indicators: DataFrame, prices_by_date: DataFrame,
                      strategies: DataFrame, companies: DataFrame, rebalance_dates: list[str]) -> DataFrame:
    """전략별로 F-Score 필터를 통과한 종목 중 7팩터 백분위 가중합산 점수(value_score) 상위
    portfolio_size종목을, 리밸런싱 시점 전체에 대해 한 번에 선정한다(Top100Service.scoreAll() 재현).
    prices_by_date는 prices_at_dates의 출력(모든 시점의 stock_code당 1행 스냅샷이 rebalance_date
    컬럼으로 이미 구분되어 있음)을 그대로 넘긴다.

    벡터화: 원래는 이 함수가 rebalance_date 스칼라 하나만 받아 시점 하나를 처리했고, 호출부
    (run_for_rebalance_group)가 시점 수만큼 파이썬 for문으로 이 함수를 호출해 unionByName으로
    이어붙였다. 시점마다 crossJoin(전략)을 포함한 서브플랜이 하나씩 생겨, union할 때마다 Catalyst가
    전체 트리를 재분석하는 비용이 초선형으로 쌓였다(실측: 시점 1개->12개, 계획 길이 32,218자->
    1,010,833자, 정비례 대비 2.6배). 시점을 rebalance_date 컬럼을 가진 데이터로 다뤄 한 번의
    crossJoin(전략)으로 처리하면 계획 조각이 시점 수와 무관하게 1개로 고정된다."""
    ind = latest_valid_indicators(spark, indicators, rebalance_dates)
    ind = apply_fscore_filter(ind, companies)

    # 가격 조인은 crossJoin(전략 테이블) 이전에 수행 - 전략 수(21,870)만큼 행이 불어나기 전에
    # (종목 수 x 시점 수) 규모에서 끝내는 것이 훨씬 저렴하다. join 키에 rebalance_date를 추가해
    # 같은 종목이라도 시점이 다르면 섞이지 않게 한다. inner join: 그 시점 가격이 없는 종목은
    # 애초에 매매 불가하므로 스크리닝 대상에서 자연히 제외된다.
    ind_priced = ind.join(prices_by_date, on=["stock_code", "rebalance_date"], how="inner")
    ind_priced = compute_point_in_time_ratios(ind_priced)

    # 전략 수(21,870) x (종목 수 x 시점 수) 조합 생성. 전략 테이블이 작아(1.9MB 안팎, 컬럼 10개
    # 안팎) autoBroadcastJoinThreshold(10MB)보다 작으므로 broadcast로 셔플을 없앤다.
    joined = ind_priced.crossJoin(F.broadcast(strategies))

    # 랭킹은 (전략, 리밸런싱 시점) 단위로 매겨야 한다 - "전략"이 이제 가중치프리셋까지 포함한
    # 식별자(name)이므로, name이 프리셋을 유일하게 식별하는 한 이 partitionBy 패턴이 그대로
    # 안전하다. rebalance_date를 빠뜨리면 서로 다른 시점의 종목들이 한 랭킹에 섞이는 조용한
    # 버그가 된다.
    partition_cols = ["name", "rebalance_date"]
    joined = add_percentile_rank(joined, "per_t", "per_pctl", partition_cols,
                                  ascending=True, fill_value=float("inf"))
    joined = add_percentile_rank(joined, "pbr_t", "pbr_pctl", partition_cols,
                                  ascending=True, fill_value=float("inf"))
    joined = add_percentile_rank(joined, "debt_ratio", "debt_ratio_pctl", partition_cols,
                                  ascending=True, fill_value=float("inf"))
    joined = add_percentile_rank(joined, "roe", "roe_pctl", partition_cols,
                                  ascending=False, fill_value=float("-inf"))
    joined = add_percentile_rank(joined, "roa", "roa_pctl", partition_cols,
                                  ascending=False, fill_value=float("-inf"))
    joined = add_percentile_rank(joined, "eps_growth_rate", "eps_growth_pctl", partition_cols,
                                  ascending=False, fill_value=float("-inf"))
    joined = add_percentile_rank(joined, "momentum", "momentum_pctl", partition_cols,
                                  ascending=False, fill_value=float("-inf"))

    scored = joined.withColumn(
        "value_score",
        F.col("per_pctl") * F.col("weight_per")
        + F.col("pbr_pctl") * F.col("weight_pbr")
        + F.col("roe_pctl") * F.col("weight_roe")
        + F.col("roa_pctl") * F.col("weight_roa")
        + F.col("debt_ratio_pctl") * F.col("weight_debt_ratio")
        + F.col("eps_growth_pctl") * F.col("weight_eps_growth")
        + F.col("momentum_pctl") * F.col("weight_momentum"),
    )

    w_rank = Window.partitionBy(*partition_cols).orderBy(F.col("value_score").desc())
    ranked = scored.withColumn("rank", F.row_number().over(w_rank))
    selected = ranked.filter(F.col("rank") <= F.col("portfolio_size"))

    return selected.select("name", "stock_code", "portfolio_size", "rebalance_date")


def calculate_period_returns(spark: SparkSession, portfolios: DataFrame, prices_by_date: DataFrame,
                              rebalance_dates: list[str], split_flags: DataFrame) -> DataFrame:
    """각 전략의 리밸런싱 구간(rebalance_dates[i] 매수 -> rebalance_dates[i+1] 매도)별 포트폴리오 수익률.
    동일비중(1/portfolio_size) 가정, 구간 내 종목별 수익률의 단순평균.

    벡터화: 원래는 구간(buy_date, sell_date) 쌍마다 파이썬 for문으로 반복하며 buy_prices/sell_prices를
    필터링하고 결과를 unionByName으로 이어붙였다. screen_portfolio와 같은 이유로 구간 수만큼 계획
    조각이 쌓여 드라이버 부담이 커진다. 구간 목록 자체를 DataFrame(period_start, period_end)으로
    만들어, prices_by_date를 매수가/매도가 두 번 조인하는 방식으로 한 번에 처리한다.

    액면분할/병합 의심 종목 제외 (2026-07-31 notebooks/check_04_02_backtest.ipynb 진단에서 발견,
    본 코드에 반영): 종목 101140이 2023-10-23 하루 만에 467원->9,340원(+1900%)으로 뛴 게
    02_clean_prices.py의 flag_split_suspects()에는 정확히 플래그됐지만, 04번이 cleaned/prices의
    close_price만 읽고 split_suspected 컬럼 자체를 참조하지 않아 그대로 백테스트에 반영됐다
    (per8_pbr1.2_dynone_monthly_n10의 2023-09~10 구간 수익률이 이 한 종목에 지배됨. n이 작을수록
    소수 종목 이상치에 취약해 n10 계열 성과가 부풀려진 원인 중 하나로 확인됨). 보유 구간
    (period_start, period_end] 안에 그 종목의 split_suspected=true 날짜가 하루라도 있으면
    그 구간의 그 종목 수익률을 통째로 제외한다."""
    periods = list(zip(rebalance_dates[:-1], rebalance_dates[1:]))
    periods_df = spark.createDataFrame(periods, ["period_start", "period_end"])

    buy_prices = prices_by_date.select(
        "stock_code", F.col("price").alias("buy_price"),
        F.col("rebalance_date").alias("period_start"),
    )
    sell_prices = prices_by_date.select(
        "stock_code", F.col("price").alias("sell_price"),
        F.col("rebalance_date").alias("period_end"),
    )

    # portfolios.rebalance_date(매수 시점)를 구간의 period_start와 맞춰 그 구간의 period_end를
    # 함께 붙인다. 이후 buy_prices/sell_prices 조인 키에 period_start/period_end를 포함시켜
    # 서로 다른 구간의 가격이 섞이지 않게 한다.
    with_period = portfolios.join(
        F.broadcast(periods_df), portfolios["rebalance_date"] == periods_df["period_start"], "inner"
    )

    with_prices = with_period.join(buy_prices, on=["stock_code", "period_start"], how="inner") \
        .join(sell_prices, on=["stock_code", "period_end"], how="inner")

    # (종목, 구간) 조합이 이상치인지 미리 distinct로 판정한 뒤 left-anti join으로 제외한다.
    # split_flags(종목,날짜)를 구간과 직접 조인하면 구간 안 플래그 일수만큼 행이 불어나므로
    # (fan-out), 반드시 먼저 (stock_code, period_start, period_end) 단위로 좁힌다.
    stock_periods = with_prices.select("stock_code", "period_start", "period_end").distinct()
    outlier_periods = stock_periods.alias("p").join(
        split_flags.alias("f"),
        (F.col("p.stock_code") == F.col("f.stock_code"))
        & (F.col("f.flag_date") > F.col("p.period_start"))
        & (F.col("f.flag_date") <= F.col("p.period_end")),
        how="inner",
    ).select("p.stock_code", "p.period_start", "p.period_end").distinct()

    with_prices = with_prices.join(
        outlier_periods, on=["stock_code", "period_start", "period_end"], how="left_anti"
    )

    stock_return = (F.col("sell_price") - F.col("buy_price")) / F.col("buy_price")
    with_return = with_prices.withColumn("stock_return", stock_return)

    # 구간이 섞여 있으므로 집계도 (전략, 구간) 단위로 묶어야 한다. period_start/period_end를
    # 빠뜨리면 서로 다른 구간의 종목 수익률이 한 그룹에 섞이는 조용한 버그가 된다.
    period_return = with_return.groupBy("name", "period_start", "period_end").agg(
        F.avg("stock_return").alias("period_return"),
        F.count("stock_code").alias("held_count"),
    )
    return period_return


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


def run_for_rebalance_group(spark: SparkSession, indicators: DataFrame, prices: DataFrame,
                             strategies: DataFrame, companies: DataFrame, years: list[str], rebalance: str,
                             split_flags: DataFrame) -> tuple[DataFrame, DataFrame]:
    """monthly 또는 quarterly 그룹 전략들에 대해 리밸런싱 시점 생성부터 성과 요약까지 전체 파이프라인 실행.
    시점 수(monthly 36개, quarterly 12개)만큼 screen_portfolio를 반복 호출해 union하면 DataFrame
    실행계획(lineage)이 시점 수만큼 계속 이어붙어 드라이버에서 OutOfMemoryError가 발생했다(실측 확인).
    screen_portfolio가 시점 목록 전체를 한 번에 처리하도록 벡터화했으므로 이제 이 함수에는 시점별
    반복이 남아있지 않다. cache()는 여전히 이후 단계(calculate_period_returns, summarize_performance)
    에서의 재스캔을 막기 위해 유지한다."""
    rebalance_dates = build_rebalance_dates(years, rebalance)
    print(f"[{rebalance}] 리밸런싱 시점 {len(rebalance_dates)}개: {rebalance_dates[0]} ~ {rebalance_dates[-1]}")

    group_strategies = strategies.filter(F.col("rebalance") == rebalance)
    prices_by_date = prices_at_dates(spark, prices, rebalance_dates).cache()

    # 마지막 시점은 매도 전용이라 스크리닝(매수) 대상이 아니다 - 기존 for문이 rebalance_dates[:-1]만
    # 순회하던 것과 동일한 의미로, screen_portfolio에 넘기는 시점 목록에서 마지막 시점을 제외한다.
    buy_dates = rebalance_dates[:-1]
    buy_prices_by_date = prices_by_date.filter(F.col("rebalance_date").isin(buy_dates))
    portfolios = screen_portfolio(spark, indicators, buy_prices_by_date, group_strategies, companies, buy_dates)
    portfolios = portfolios.cache()
    portfolios.count()  # 캐시를 즉시 실체화해 이후 단계에서 lineage 재계산이 일어나지 않게 함

    period_returns = calculate_period_returns(spark, portfolios, prices_by_date, rebalance_dates, split_flags).cache()
    period_returns.count()  # 이후 summarize_performance에서 재스캔하지 않도록 여기서 실체화

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

    # induty_code는 --market 필터와 무관하게 F-Score 금융업 예외 판정(apply_fscore_filter)에
    # 항상 필요하다. --market이 걸리면 companies 자체를 그 시장으로 좁혀서 induty_code 조인
    # 대상도 자연히 좁아지게 한다(사용자 확인: "F-Score/금융업 필터는 --market과 별개로 항상 적용,
    # 단 04번이 코스피를 추가로 하드코딩하지는 않음" - 기존 --market 옵션 이상으로 시장을 제한하지 않음).
    companies = spark.read.parquet(args.companies_dir).select("stock_code", "corp_cls", "induty_code")
    if args.market != "ALL":
        corp_cls = "Y" if args.market == "KOSPI" else "K"
        companies = companies.filter(F.col("corp_cls") == corp_cls)
        indicators = indicators.join(F.broadcast(companies.select("stock_code")), on="stock_code", how="inner")
        print(f"--market {args.market} 필터 적용: 대상 종목 {companies.count()}개")
    companies = companies.cache()

    # monthly(36개)+quarterly(12개) 총 48개 리밸런싱 시점에서 반복 참조되므로 캐싱해 재스캔 방지
    indicators = indicators.cache()
    prices = spark.read.parquet(args.prices_dir)

    # 액면분할/병합 의심 종목 제외용 (종목, 날짜) 목록. cleaned/prices의 split_suspected는
    # 02_clean_prices.py가 하루 단위로 찍는 플래그이므로 여기서 True인 날짜만 미리 추려
    # calculate_period_returns가 두 리밸런싱 그룹에서 반복 조인할 수 있게 한다.
    split_flags = prices.filter(F.col("split_suspected") == True).select(
        "stock_code", F.col("bas_dt").alias("flag_date")
    ).cache()
    print(f"액면분할/병합 의심 플래그: {split_flags.count()}건")

    period_returns_all = []
    summary_all = []
    for rebalance in ("monthly", "quarterly"):
        period_returns, summary = run_for_rebalance_group(
            spark, indicators, prices, strategies, companies, years, rebalance, split_flags
        )
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
    companies.unpersist()
    split_flags.unpersist()

    spark.stop()


if __name__ == "__main__":
    main()
