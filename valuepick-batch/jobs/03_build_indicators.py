"""
03_build_indicators.py - 가치투자 지표 계산 (PROJECT_INSTRUCTIONS.md 4.3)

역할: EPS/BPS/PER/PBR/ROE/부채비율/배당수익률/ROA/모멘텀/F-Score/EPS성장률을 계산한다.
기존 Spring Boot FinancialIndicatorService.java + DartFinancialCollector.java의 계산/매핑 로직을
그대로 재현하는 것이 목표이며, notebooks/verify_indicators.ipynb에서 대조 검증한다.

왜 Spark인가: Window.partitionBy("stock_code").orderBy("year")로 종목별 시계열(당기 대비 전기) 비교가
             전체 상장 종목에 걸쳐 병렬로 이루어진다.

입력:
  - data/raw/financials  (계정id/계정명 단위 원본 - 종목당 여러 행)
  - data/cleaned/prices  (snapshot_type: current/1m_ago/12m_ago)
  - data/raw/dividends
  - data/raw/companies
출력: data/indicators (year 파티셔닝, rcept_no 포함 - 04번 백테스트의 룩어헤드 바이어스 방지용 공시일)

한계 (기존 Java 로직과 다른 지점, notebooks/verify_indicators.ipynb에서 반드시 확인할 것):
  - 환율: 한국수출입은행 API로 실시간 환율을 조회한다. Java는 DB에 누적된 일별 환율 중 최신값을 쓰지만
    이 파이프라인은 매 실행 시점의 "현재" 환율을 쓰므로, 외화표시 재무제표(CNY 등) 종목은 계산 시점에
    따라 결과가 달라질 수 있다.
  - 금융업 F-Score 스킵: Java는 Company.isFinancialIndustry()(업종코드 64/65/66)로 금융업을 판별해
    F-Score를 null 처리하지만, 01_ingest_raw.py가 받는 KRX 응답에는 업종코드 필드가 없어 이 판별이 불가능하다.
    따라서 이 파이프라인은 업종 구분 없이 전 종목에 F-Score를 계산한다.
  - 모멘텀 기준일: 여러 리밸런싱 시점이 아니라 01_ingest_raw.py 실행 시점(가장 최근 수집일) 하나에 대해서만
    1개월전/12개월전 스냅샷을 수집하므로, 모멘텀/F-Score(신주발행 여부)는 그 시점 기준 스냅샷 하나로 계산된다.
"""

from __future__ import annotations

import argparse
import os

import requests
from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F
from pyspark.sql.window import Window

# ── DartFinancialCollector.java의 계정 매핑 상수 그대로 이식 ────────────────
# account_id(표준 코드) 우선, 없으면 account_nm(한글 계정명) 후보로 재시도하는 2단계 매칭
ACC_REVENUE = ["ifrs-full_Revenue", "ifrs-full_InsuranceRevenue"]
ACC_OPERATING_INCOME = ["dart_OperatingIncomeLoss", "ifrs-full_ProfitLossFromOperatingActivities"]
ACC_NET_INCOME = ["ifrs-full_ProfitLoss"]
ACC_TOTAL_ASSETS = ["ifrs-full_Assets"]
ACC_TOTAL_LIABILITIES = ["ifrs-full_Liabilities"]
ACC_TOTAL_EQUITY = ["ifrs-full_Equity"]
ACC_CURRENT_ASSETS = ["ifrs-full_CurrentAssets"]
ACC_CURRENT_LIABILITIES = ["ifrs-full_CurrentLiabilities"]
ACC_OPERATING_CASH_FLOW = ["ifrs-full_CashFlowsFromUsedInOperatingActivities"]
ACC_GROSS_PROFIT = ["ifrs-full_GrossProfit"]

NM_REVENUE = ["매출액", "영업수익", "영업수익(손실)", "수익(매출액)"]
NM_OPERATING_INCOME = ["영업이익", "영업이익(손실)"]
NM_NET_INCOME = ["당기순이익(손실)", "당기순이익", "당기순손익"]
NM_TOTAL_ASSETS = ["자산총계"]
NM_TOTAL_LIABILITIES = ["부채총계"]
NM_TOTAL_EQUITY = ["자본총계"]
NM_CURRENT_ASSETS = ["유동자산"]
NM_CURRENT_LIABILITIES = ["유동부채"]
NM_OPERATING_CASH_FLOW = ["영업활동현금흐름", "영업활동으로 인한 현금흐름"]
NM_GROSS_PROFIT = ["매출총이익", "매출총이익(손실)"]

NO_STANDARD_ACCOUNT_ID = "-표준계정코드 미사용-"

# pick 결과 컬럼명 -> (account_id 후보, account_nm 후보, 허용 sj_div 목록)
#
# 왜 sj_div 제한이 필요한가: DartItem.java에는 sj_div(재무제표 구분: BS/CIS/IS/CF/SCE) 필드가 아예 없어서
# Java는 account_id만으로 HashMap에 "처음 만난 값"을 채택한다(API 응답 순서 의존). 이 파이프라인은
# API 응답을 Spark로 분산 병렬 처리하므로 원본 순서가 보존되지 않아 "첫 값"이 Java와 달라질 수 있다.
# 예: ifrs-full_Equity는 재무상태표(BS)뿐 아니라 자본변동표(SCE)에도 지분 항목별로 수백 건 중복 등장하는데,
# 순서 의존 로직으로는 SCE의 엉뚱한 하위 항목을 자본총계로 잘못 채택하는 사례가 실제로 발생했다(검증 중 발견).
# sj_div로 재무제표 종류를 명시 필터링하면 이 순서 의존성 자체가 사라져 Java보다 오히려 더 안정적이다.
FIELD_ACCOUNT_CANDIDATES = {
    "revenue": (ACC_REVENUE, NM_REVENUE, ["IS", "CIS"]),
    "operating_income": (ACC_OPERATING_INCOME, NM_OPERATING_INCOME, ["IS", "CIS"]),
    "net_income": (ACC_NET_INCOME, NM_NET_INCOME, ["IS", "CIS"]),
    "total_assets": (ACC_TOTAL_ASSETS, NM_TOTAL_ASSETS, ["BS"]),
    "total_liabilities": (ACC_TOTAL_LIABILITIES, NM_TOTAL_LIABILITIES, ["BS"]),
    "total_equity": (ACC_TOTAL_EQUITY, NM_TOTAL_EQUITY, ["BS"]),
    "current_assets": (ACC_CURRENT_ASSETS, NM_CURRENT_ASSETS, ["BS"]),
    "current_liabilities": (ACC_CURRENT_LIABILITIES, NM_CURRENT_LIABILITIES, ["BS"]),
    "operating_cash_flow": (ACC_OPERATING_CASH_FLOW, NM_OPERATING_CASH_FLOW, ["CF"]),
    "gross_profit": (ACC_GROSS_PROFIT, NM_GROSS_PROFIT, ["IS", "CIS"]),
}

# ExchangeRateApiService.CURRENCY_UNIT_MAP과 동일 - DART currency 코드 -> 한국수출입은행 cur_unit
CURRENCY_UNIT_MAP = {
    "KRW": "KRW",
    "USD": "USD",
    "CNY": "CNH",
    "JPY": "JPY(100)",
    "IDR": "IDR(100)",
}
UNIT_100_CURRENCIES = {"JPY(100)", "IDR(100)"}
EXIM_URL = "https://oapi.koreaexim.go.kr/site/program/financial/exchangeJSON"

MAX_VALID_DIVIDEND_YIELD = 100.0  # resolveDividendYield와 동일 - 비정상적으로 큰 배당수익률은 공시 오류로 간주해 제외


# ── 재무제표 3개년 스택 ──────────────────────────────────────────────────
def stack_financial_years(fin: DataFrame) -> DataFrame:
    """당기(thstrm)/전기(frmtrm)/전전기(bfefrmtrm) 3개 컬럼 세트를 stack()으로 행 단위 언피벗.
    DART 응답 한 건에 이미 3개년 수치가 다 있으므로 추가 API 호출 없이 3개년 시계열을 확보한다.
    rcept_no(접수번호, 앞 8자리가 실제 공시일 YYYYMMDD)도 함께 흘려보낸다 - 04번 백테스트의
    룩어헤드 바이어스 방지 조인(report_date <= rebalance_date)에 필요."""
    return fin.select(
        "stock_code", "corp_code", "fs_div", "sj_div", "account_id", "account_nm", "currency", "rcept_no",
        F.expr("""
            stack(3,
                CAST(bsns_year AS INT),     thstrm_amount,
                CAST(bsns_year AS INT) - 1, frmtrm_amount,
                CAST(bsns_year AS INT) - 2, bfefrmtrm_amount
            ) AS (year, amount)
        """),
    )


def parse_amount(col: str) -> F.Column:
    """DartFinancialCollector.parseAmount와 동일 - null/공백/'-'는 0, 콤마 제거 후 정수부만 사용"""
    cleaned = F.regexp_replace(F.trim(F.col(col)), ",", "")
    return F.when((cleaned == "") | (cleaned == "-") | cleaned.isNull(), F.lit(0)) \
        .otherwise(F.round(cleaned.cast("double")).cast("long"))


def pivot_financials(stacked: DataFrame) -> DataFrame:
    """stock_code x fs_div x year 단위로, account_id 우선/account_nm 차선 매칭해 재무 항목 컬럼을 만든다
    (DartFinancialCollector.pick과 동일한 2단계 매칭. 회사마다 표준 코드 사용 여부가 달라 이 fallback이 필요)"""
    amount = parse_amount("amount")
    valid_id = (F.col("account_id").isNotNull()) & (F.col("account_id") != "") & (F.col("account_id") != NO_STANDARD_ACCOUNT_ID)

    by_id = stacked.filter(valid_id).withColumn("amount_num", amount)
    by_nm = stacked.filter(F.col("account_nm").isNotNull() & (F.col("account_nm") != "")).withColumn("amount_num", amount)

    keys = ["stock_code", "fs_div", "year"]
    result = stacked.select(*keys).distinct()

    for field, (id_candidates, nm_candidates, allowed_sj_div) in FIELD_ACCOUNT_CANDIDATES.items():
        # sj_div로 재무제표 종류를 먼저 좁혀서 동명이인 계정(예: SCE의 Equity 하위 항목) 오매칭을 원천 차단
        # (Java는 sj_div를 안 받아 API 응답 순서 첫 값에 의존하지만, 우리는 sj_div가 있어 더 정확하게 특정 가능)
        id_match = by_id.filter(F.col("account_id").isin(id_candidates) & F.col("sj_div").isin(allowed_sj_div)) \
            .groupBy(*keys).agg(F.first("amount_num", ignorenulls=True).alias(f"{field}_by_id"))
        nm_match = by_nm.filter(F.col("account_nm").isin(nm_candidates) & F.col("sj_div").isin(allowed_sj_div)) \
            .groupBy(*keys).agg(F.first("amount_num", ignorenulls=True).alias(f"{field}_by_nm"))

        result = result.join(id_match, on=keys, how="left").join(nm_match, on=keys, how="left")
        # id 매칭 우선, 없으면 nm 매칭, 둘 다 없으면 0 (Java pick()의 기본값과 동일)
        result = result.withColumn(
            field,
            F.coalesce(F.col(f"{field}_by_id"), F.col(f"{field}_by_nm"), F.lit(0)),
        ).drop(f"{field}_by_id", f"{field}_by_nm")

    # currency는 계정별로 다를 수 없으므로 그룹 내 첫 값 사용
    currency = stacked.filter(F.col("currency").isNotNull()).groupBy(*keys) \
        .agg(F.first("currency", ignorenulls=True).alias("currency"))
    result = result.join(currency, on=keys, how="left")

    # 같은 year라도 "당해연도 사업보고서 자체"와 "다음연도 사업보고서의 전기(frmtrm) 데이터"
    # 양쪽에서 유래할 수 있어 rcept_no가 여러 개일 수 있음 - 더 일찍 공시된(가장 오래된) 값을 채택
    # (룩어헤드 바이어스 방지 관점에서 보수적인 선택: 실제로 그 정보를 알 수 있었던 가장 이른 시점)
    earliest_rcept = stacked.filter(F.col("rcept_no").isNotNull()).groupBy(*keys) \
        .agg(F.min("rcept_no").alias("rcept_no"))
    result = result.join(earliest_rcept, on=keys, how="left")

    return result


def prefer_cfs_over_ofs(fin_pivoted: DataFrame) -> DataFrame:
    """fs_div=CFS(연결) 우선, 없으면 OFS(별도) 사용 (FinancialIndicatorService의 CFS->OFS 재시도와 동일)"""
    w = Window.partitionBy("stock_code", "year").orderBy(F.when(F.col("fs_div") == "CFS", 0).otherwise(1))
    return fin_pivoted.withColumn("_rank", F.row_number().over(w)) \
        .filter(F.col("_rank") == 1).drop("_rank")


# ── 환율 조회 (한국수출입은행 API) ───────────────────────────────────────
def fetch_exim_rates(exim_api_key: str, search_date: str) -> dict:
    """ExchangeRateApiService.callExchangeApi와 동일 로직. 주말/공휴일이면 빈 응답이라 호출부에서 하루씩 재시도."""
    resp = requests.get(EXIM_URL, params={
        "authkey": exim_api_key, "searchdate": search_date, "data": "AP01",
    }, timeout=30)
    resp.raise_for_status()
    body = resp.json()
    if not body:
        return {}

    rates = {"KRW": 1.0}  # KRW는 응답에 없으므로 고정값 추가 (buildKoreaExchange와 동일)
    for item in body:
        cur_unit = item.get("cur_unit")
        deal_bas_r = item.get("deal_bas_r")
        if not cur_unit or deal_bas_r is None:
            continue
        try:
            rates[cur_unit] = float(str(deal_bas_r).replace(",", "").strip())
        except ValueError:
            continue
    return rates


def resolve_exchange_rates(exim_api_key: str, base_date_str: str) -> dict:
    """currency(DART 표기) -> KRW 환율 맵. getRateToKrw와 동일하게 100단위 통화는 100으로 나눔."""
    from datetime import datetime, timedelta
    candidate = datetime.strptime(base_date_str, "%Y%m%d").date()

    raw_rates = {}
    for _ in range(10):  # findPreviousBusinessDayRates와 동일하게 최대 10일 역탐색
        raw_rates = fetch_exim_rates(exim_api_key, candidate.strftime("%Y%m%d"))
        if raw_rates:
            break
        candidate -= timedelta(days=1)

    result = {}
    for dart_currency, cur_unit in CURRENCY_UNIT_MAP.items():
        rate = raw_rates.get(cur_unit)
        if rate is None:
            continue
        if cur_unit in UNIT_100_CURRENCIES:
            rate = rate / 100.0
        result[dart_currency] = rate
    return result


# ── 지표 계산 ─────────────────────────────────────────────────────────────
def build_indicator_base(fin: DataFrame, exchange_rates: dict) -> DataFrame:
    """EPS/BPS/PER/PBR/ROE/부채비율/ROA 계산 - FinancialIndicatorService.calculate와 동일 순서/조건"""
    rate_map_expr = F.create_map(*[x for cur, rate in exchange_rates.items() for x in (F.lit(cur), F.lit(rate))])
    fx_rate = F.coalesce(rate_map_expr[F.col("currency")], F.lit(1.0))  # 매핑 안 된 통화는 1.0(=변환 안 함)

    df = fin.withColumn("fx_rate", fx_rate) \
        .withColumn("net_income_krw", F.round(F.col("net_income") * F.col("fx_rate")).cast("long")) \
        .withColumn("equity_krw", F.round(F.col("total_equity") * F.col("fx_rate")).cast("long")) \
        .withColumn("liabilities_krw", F.round(F.col("total_liabilities") * F.col("fx_rate")).cast("long"))

    return df


def join_latest_price(fin: DataFrame, prices_current: DataFrame) -> DataFrame:
    """종목별 가장 최근(current) 종가/상장주식수 결합 - findTopBySrtnCdOrderByBasDtDesc와 동일"""
    w = Window.partitionBy("stock_code").orderBy(F.desc("bas_dt"))
    latest = prices_current.withColumn("_rank", F.row_number().over(w)) \
        .filter(F.col("_rank") == 1) \
        .select("stock_code", "close_price", F.col("listed_share_count").alias("share_count"))
    return fin.join(latest, on="stock_code", how="inner")  # 주가 없는 종목은 계산 불가(Java도 skip)


def calculate_core_ratios(df: DataFrame) -> DataFrame:
    df = df.withColumn("eps", F.when(F.col("share_count") != 0,
                                      F.round(F.col("net_income_krw") / F.col("share_count"), 2)))

    df = df.withColumn("bps", F.when(F.col("equity_krw") > 0,
                                      F.round(F.col("equity_krw") / F.col("share_count"), 2)))
    df = df.withColumn("pbr", F.when((F.col("equity_krw") > 0) & (F.col("bps") != 0),
                                      F.round(F.col("close_price") / F.col("bps"), 2)))
    df = df.withColumn("roe", F.when(F.col("equity_krw") > 0,
                                      F.round(F.col("net_income_krw") / F.col("equity_krw") * 100, 2)))
    df = df.withColumn("debt_ratio", F.when(F.col("equity_krw") > 0,
                                             F.round(F.col("liabilities_krw") / F.col("equity_krw") * 100, 2)))

    # 적자 기업(eps<=0)은 PER N/A - 음수 EPS로 계산된 PER은 실무에서 의미 없음(네이버 증권 등 동일 처리)
    df = df.withColumn("per", F.when(F.col("eps") > 0, F.round(F.col("close_price") / F.col("eps"), 2)))

    df = df.withColumn("roa", F.when(F.col("total_assets") > 0,
                                      F.round(F.col("net_income") / F.col("total_assets") * 100, 2)))
    return df


def join_dividend_yield(df: DataFrame, dividends: DataFrame) -> DataFrame:
    """resolveDividendYield와 동일 - se='주당 현금배당금(원)', stock_knd='보통주' 정확 매칭 후 종가 대비 비율 계산.
    같은 (stock_code, year)에 값이 있는 행과 공백(-) 행이 동시에 존재하는 경우가 실제로 있어(원본 DART
    배당 API 응답 자체의 중복), groupBy + F.first(ignorenulls=True)로 값이 있는 행 하나만 채택해
    join 시 fan-out(1행이 여러 행으로 복제)되는 것을 방지한다."""
    # parse_amount는 '-'/공백도 0으로 바꿔 non-null이 되어 F.first(ignorenulls=True)의 순서
    # 의존성 문제(Spark 분산 처리 특성상 정렬 후 groupBy도 순서 보장 안 됨)를 그대로 겪으므로,
    # thstrm이 실제 값을 가진 행의 dividend_amount만 남기고 없는 행은 null로 만든 뒤 first(ignorenulls=True)로
    # 값이 있으면 그 값을, 전부 없으면 null을 채택한다 (0으로 잘못 채택되는 것을 원천 차단)
    has_value = F.trim(F.col("thstrm")).isNotNull() & (F.trim(F.col("thstrm")) != "") & (F.trim(F.col("thstrm")) != "-")
    common_dividend = dividends.filter(
        (F.col("se") == "주당 현금배당금(원)") & (F.col("stock_knd") == "보통주")
    ).withColumn("dividend_amount", F.when(has_value, parse_amount("thstrm"))) \
     .groupBy("stock_code", F.col("year").alias("dividend_year")) \
     .agg(F.first("dividend_amount", ignorenulls=True).alias("dividend_amount"))

    df = df.join(common_dividend, (df.stock_code == common_dividend.stock_code)
                 & (df.year == common_dividend.dividend_year), how="left") \
        .drop(common_dividend.stock_code).drop("dividend_year")

    df = df.withColumn(
        "dividend_yield",
        F.when((F.col("close_price") != 0) & F.col("dividend_amount").isNotNull(),
               F.round(F.col("dividend_amount") / F.col("close_price") * 100, 2)),
    )
    # DART 공시 오류로 배당수익률이 비정상적으로 크게 계산되는 경우 제외 (예: 와이엔텍)
    df = df.withColumn(
        "dividend_yield",
        F.when(F.col("dividend_yield") <= MAX_VALID_DIVIDEND_YIELD, F.col("dividend_yield")),
    )
    return df


def _latest_snapshot(prices: DataFrame) -> DataFrame:
    """01_ingest_raw.py를 여러 번 실행하면 종목당 1m_ago/12m_ago 스냅샷이 bas_dt별로 계속 누적되므로,
    종목당 가장 최근(bas_dt 최대) 스냅샷 1건만 남긴다 (여러 건이 남으면 join_momentum에서 카티전 곱으로
    fan-out되어 03번 결과 전체가 중복되는 문제가 실제로 발생했음)."""
    w = Window.partitionBy("stock_code").orderBy(F.desc("bas_dt"))
    return prices.withColumn("_rank", F.row_number().over(w)).filter(F.col("_rank") == 1).drop("_rank")


def join_momentum(df: DataFrame, prices_1m: DataFrame, prices_12m: DataFrame) -> DataFrame:
    """모멘텀 = (1개월전 종가 - 12개월전 종가) / 12개월전 종가. resolveMomentum과 동일 (12-1 모멘텀)"""
    p1m = _latest_snapshot(prices_1m).select("stock_code", F.col("close_price").alias("price_1m_ago"),
                            F.col("listed_share_count").alias("share_count_1m_ago"))
    p12m = _latest_snapshot(prices_12m).select("stock_code", F.col("close_price").alias("price_12m_ago"),
                              F.col("listed_share_count").alias("share_count_12m_ago"))

    df = df.join(p1m, on="stock_code", how="left").join(p12m, on="stock_code", how="left")
    df = df.withColumn(
        "momentum",
        F.when(F.col("price_12m_ago") > 0,
               F.round((F.col("price_1m_ago") - F.col("price_12m_ago")) / F.col("price_12m_ago") * 100, 2)),
    )
    return df


def calculate_eps_growth_rate(df: DataFrame, prev_year_df: DataFrame) -> DataFrame:
    """EPS성장률 = (당기 EPS - 전기 EPS) / |전기 EPS| x 100. 전기 EPS = 전년도 순이익 / 1년전 상장주식수
    (resolveEpsGrowthRate와 동일 - 1년전 상장주식수는 momentum 계산용 12m_ago 스냅샷을 재사용)"""
    prev = prev_year_df.select(
        "stock_code",
        F.col("net_income_krw").alias("prev_net_income_krw"),
    )
    df = df.join(prev, on="stock_code", how="left")
    df = df.withColumn(
        "prev_eps",
        F.when(F.col("share_count_12m_ago") > 0,
               F.col("prev_net_income_krw") / F.col("share_count_12m_ago")),
    )
    # 전기가 적자(prevEps<=0)면 성장률 부호가 정의되지 않아 계산 스킵 (resolveEpsGrowthRate와 동일)
    df = df.withColumn(
        "eps_growth_rate",
        F.when(F.col("prev_eps") > 0,
               F.round((F.col("eps") - F.col("prev_eps")) / F.abs(F.col("prev_eps")) * 100, 2)),
    )
    return df.drop("prev_net_income_krw", "prev_eps")


def calculate_f_score(df: DataFrame, prev_year_df: DataFrame) -> DataFrame:
    """Piotroski F-Score(0~9) - resolveFScore와 동일한 9개 항목.
    전년도 재무제표가 없으면(prev_year_df에 매칭 없음) null. 업종별 스킵(금융업)은 이 파이프라인에서는 미적용(모듈 docstring 참고)."""
    prev = prev_year_df.select(
        "stock_code",
        F.col("total_assets").alias("prev_total_assets"),
        F.col("net_income").alias("prev_net_income"),
        F.col("total_liabilities").alias("prev_total_liabilities"),
        F.col("total_equity").alias("prev_total_equity"),
        F.col("current_assets").alias("prev_current_assets"),
        F.col("current_liabilities").alias("prev_current_liabilities"),
        F.col("gross_profit").alias("prev_gross_profit"),
        F.col("revenue").alias("prev_revenue"),
    )
    df = df.join(prev, on="stock_code", how="left")

    prev_roa = F.when(F.col("prev_total_assets") > 0,
                       F.col("prev_net_income") / F.col("prev_total_assets") * 100)
    curr_debt_ratio = F.when(F.col("total_equity") > 0, F.col("total_liabilities") / F.col("total_equity"))
    prev_debt_ratio = F.when(F.col("prev_total_equity") > 0, F.col("prev_total_liabilities") / F.col("prev_total_equity"))
    curr_current_ratio = F.when(F.col("current_liabilities") > 0, F.col("current_assets") / F.col("current_liabilities"))
    prev_current_ratio = F.when(F.col("prev_current_liabilities") > 0, F.col("prev_current_assets") / F.col("prev_current_liabilities"))
    curr_gross_margin = F.when(F.col("revenue") > 0, F.col("gross_profit") / F.col("revenue"))
    prev_gross_margin = F.when(F.col("prev_revenue") > 0, F.col("prev_gross_profit") / F.col("prev_revenue"))
    curr_asset_turnover = F.when(F.col("total_assets") > 0, F.col("revenue") / F.col("total_assets"))
    prev_asset_turnover = F.when(F.col("prev_total_assets") > 0, F.col("prev_revenue") / F.col("prev_total_assets"))

    score = (
        F.when(F.col("roa") > 0, 1).otherwise(0)
        + F.when(F.col("operating_cash_flow") > 0, 1).otherwise(0)
        + F.when(F.col("roa") > prev_roa, 1).otherwise(0)
        + F.when(F.col("operating_cash_flow") > F.col("net_income"), 1).otherwise(0)
        + F.when(curr_debt_ratio < prev_debt_ratio, 1).otherwise(0)
        + F.when(curr_current_ratio > prev_current_ratio, 1).otherwise(0)
        + F.when(F.col("share_count") <= F.col("share_count_12m_ago"), 1).otherwise(0)
        + F.when(curr_gross_margin > prev_gross_margin, 1).otherwise(0)
        + F.when(curr_asset_turnover > prev_asset_turnover, 1).otherwise(0)
    )

    has_prev = F.col("prev_total_assets").isNotNull()
    df = df.withColumn("f_score", F.when(has_prev, score))
    return df.drop("prev_total_assets", "prev_net_income", "prev_total_liabilities", "prev_total_equity",
                    "prev_current_assets", "prev_current_liabilities", "prev_gross_profit", "prev_revenue")


def main():
    parser = argparse.ArgumentParser(description="03_build_indicators: 가치투자 지표 계산")
    parser.add_argument("--financials-dir", default="/opt/spark-apps/data/raw/financials")
    parser.add_argument("--prices-dir", default="/opt/spark-apps/data/cleaned/prices")
    parser.add_argument("--dividends-dir", default="/opt/spark-apps/data/raw/dividends")
    parser.add_argument("--output-dir", default="/opt/spark-apps/data/indicators")
    parser.add_argument("--year", required=True, help="지표 계산 기준 연도 (재무제표 당기 연도, 예: 2023)")
    args = parser.parse_args()

    exim_api_key = os.environ["EXIM_API_KEY"]

    spark = SparkSession.builder.appName("03_build_indicators").getOrCreate()

    # ── 1. 재무제표 3개년 스택 + CFS/OFS 선택 ────────────────────────────
    fin_raw = spark.read.parquet(args.financials_dir)
    stacked = stack_financial_years(fin_raw)
    pivoted = pivot_financials(stacked)
    fin = prefer_cfs_over_ofs(pivoted)
    print(f"재무제표 3개년 스택 완료: {fin.count()}건 (stock_code x year, CFS 우선)")

    # ── 2. 환율 조회 (한국수출입은행) ─────────────────────────────────────
    prices = spark.read.parquet(args.prices_dir)
    latest_bas_dt = prices.filter(F.col("snapshot_type") == "current").agg(F.max("bas_dt")).first()[0]
    exchange_rates = resolve_exchange_rates(exim_api_key, latest_bas_dt)
    print(f"환율 조회 완료 ({latest_bas_dt} 기준): {exchange_rates}")

    fin = build_indicator_base(fin, exchange_rates)

    # ── 3. 당기(year) 재무제표에 최근 주가 결합 후 핵심 비율 계산 ──────────
    curr_year = str(args.year)
    fin_curr = fin.filter(F.col("year") == curr_year)
    prices_current = prices.filter(F.col("snapshot_type") == "current")
    df = join_latest_price(fin_curr, prices_current)
    df = calculate_core_ratios(df)
    print(f"핵심 비율(EPS/BPS/PER/PBR/ROE/부채비율/ROA) 계산 완료: {df.count()}건")

    # ── 4. 배당수익률 ─────────────────────────────────────────────────────
    dividends = spark.read.parquet(args.dividends_dir)
    df = join_dividend_yield(df, dividends)

    # ── 5. 모멘텀 (1개월전/12개월전 스냅샷) ────────────────────────────────
    prices_1m = prices.filter(F.col("snapshot_type") == "1m_ago")
    prices_12m = prices.filter(F.col("snapshot_type") == "12m_ago")
    df = join_momentum(df, prices_1m, prices_12m)

    # ── 6. 전년도 재무제표 결합 후 F-Score, EPS성장률 ──────────────────────
    fin_prev = fin.filter(F.col("year") == str(int(curr_year) - 1))
    df = calculate_f_score(df, fin_prev)
    df = calculate_eps_growth_rate(df, fin_prev)
    print(f"모멘텀/F-Score/EPS성장률 계산 완료")

    result = df.select(
        "stock_code", "year", "fs_div", "currency", "rcept_no",
        "eps", "bps", "per", "pbr", "roe", "debt_ratio", "dividend_yield", "roa",
        "momentum", "f_score", "eps_growth_rate",
        "net_income_krw", "equity_krw", "dividend_amount",
    )

    result.write.mode("overwrite").partitionBy("year").parquet(args.output_dir)
    print(f"indicators 저장 완료: {args.output_dir} ({result.count()}건)")

    spark.stop()


if __name__ == "__main__":
    main()
