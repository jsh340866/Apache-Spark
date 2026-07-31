"""
01_ingest_raw.py - 데이터 레이크 구축 (PROJECT_INSTRUCTIONS.md 4.1)

역할: DART/공공데이터포털 API 응답을 원본 그대로 받아 Parquet로 변환하고 종목/연도 기준으로 파티셔닝한다.
왜 Spark인가: 이 단계 자체는 Spark가 필수는 아니다(단순 변환). 다만 이후 단계에서 파티션 단위로
             효율적으로 읽으려면 이 단계에서부터 Spark DataFrame으로 다뤄 파티션 전략을 일관되게 가져가는 것이 유리하다.

기존 ValuePick(Spring Boot) 수집 로직을 참고했다 (엔드포인트/인증/파싱만 재사용, MySQL 저장 대신 Parquet 저장):
- StockPriceCollector.java  -> 공공데이터포털 주가 API (JSON, resultType=json)
- DartCompanyCollector.java -> KRX 상장종목 API(JSON) + DART corpCode.xml(ZIP 안에 XML, 이 응답만 XML)
- DartFinancialCollector.java -> DART 단일회사 전체 재무제표 API (JSON)
- DividendCollector.java    -> DART 배당(배당에 관한 사항) API (JSON)

절대 원칙: 기존 ValuePick의 MySQL을 거치지 않고 원천에서 직접 수집한다.
"""

from __future__ import annotations  # Spark 이미지의 Python 3.8에서도 `dict | None` 등 PEP 604 타입 힌트 사용 가능하게

import argparse
import io
import os
import time
import zipfile
from datetime import date, datetime, timedelta
from xml.etree import ElementTree

import requests
from pyspark.sql import SparkSession

# ── API 엔드포인트 (기존 Java 코드의 buildUrl들과 동일) ─────────────────────
KRX_LISTED_URL = "https://apis.data.go.kr/1160100/service/GetKrxListedInfoService/getItemInfo"
STOCK_PRICE_URL = "https://apis.data.go.kr/1160100/service/GetStockSecuritiesInfoService/getStockPriceInfo"
DART_CORP_CODE_URL = "https://opendart.fss.or.kr/api/corpCode.xml"
DART_FINANCIAL_URL = "https://opendart.fss.or.kr/api/fnlttSinglAcntAll.json"
DART_DIVIDEND_URL = "https://opendart.fss.or.kr/api/alotMatter.json"

SLEEP_SEC = 0.1  # DART API 호출 간격 (기존 DartFinancialCollector.SLEEP_MS=100과 동일, 차단 방지)
MAX_RETRY = 3    # 429 등 재시도 횟수 (StockPriceCollector.requestApiWithRetry와 동일)


def months_ago(base: date, months: int) -> date:
    """base 날짜에서 정확히 N개월 전 날짜 계산 (표준 라이브러리만 사용, 30일 근사가 아닌 실제 월 단위 이동).
    FinancialIndicatorService.resolveMomentum의 baseDt.minusMonths(N)과 동일한 의미."""
    total_month = base.year * 12 + (base.month - 1) - months
    year, month = divmod(total_month, 12)
    month += 1
    # 이동한 달의 말일보다 base.day가 크면(예: 3/31 -> 2월) 해당 월의 마지막 날로 보정
    if month == 12:
        next_month_first = date(year + 1, 1, 1)
    else:
        next_month_first = date(year, month + 1, 1)
    last_day_of_month = (next_month_first - timedelta(days=1)).day
    day = min(base.day, last_day_of_month)
    return date(year, month, day)


def request_json_with_retry(url: str, params: dict) -> dict | None:
    """공공데이터포털/DART JSON API 호출. 429(Too Many Requests) 발생 시 재시도 (기존 requestApiWithRetry 로직과 동일).
    502/503(게이트웨이/서버 일시 장애)도 동일 백오프로 재시도 - 실제로 2021-03-07 호출에서 502가 발생했으나
    직후 재요청 시 정상(resultCode=00) 응답을 받아 횟수 제한이 아닌 일시적 장애로 확인됨."""
    for i in range(MAX_RETRY):
        resp = requests.get(url, params=params, timeout=30)
        if resp.status_code in (429, 502, 503):
            wait = 3 * (i + 1)  # 3초, 6초, 9초 대기 (기존 로직과 동일한 백오프)
            time.sleep(wait)
            continue
        resp.raise_for_status()
        return resp.json()
    raise RuntimeError(f"최대 재시도 횟수 초과: {url}")


def already_ingested(spark: SparkSession, path: str, partition_value: str, partition_col: str) -> bool:
    """이미 받은 파티션인지 확인 - DART 일일 콜 제한 대응, 재수집 방지 (PROJECT_INSTRUCTIONS.md 4.1 리스크 대응)"""
    if not os.path.exists(path):
        return False
    df = spark.read.parquet(path)
    return df.filter(df[partition_col] == partition_value).limit(1).count() > 0


def snapshot_type_exists(spark: SparkSession, path: str, snapshot_type: str) -> bool:
    """1m_ago/12m_ago 스냅샷이 이미 하나라도 있는지 확인.
    already_ingested(bas_dt 기준)는 momentum_base_dt가 실행일마다 달라져 target 날짜도 매번 바뀌므로
    재수집 방지가 사실상 작동하지 않았다(01번을 여러 번 실행할 때마다 새 bas_dt로 계속 append되어
    03_build_indicators.py의 join_momentum에서 fan-out 버그가 실제로 발생한 원인). momentum 계산은
    "정확히 N개월 전"이 아니라 최신 스냅샷 하나면 충분하므로, snapshot_type 존재 여부로 체크한다."""
    if not os.path.exists(path):
        return False
    df = spark.read.parquet(path)
    return df.filter(df["snapshot_type"] == snapshot_type).limit(1).count() > 0


def already_ingested_values(spark: SparkSession, path: str, partition_col: str) -> set[str]:
    """이미 받은 파티션 값 전체를 한 번에 조회 (날짜 범위 루프에서 매 반복마다 전체 스캔하는 것 방지)"""
    if not os.path.exists(path):
        return set()
    df = spark.read.parquet(path)
    return {row[partition_col] for row in df.select(partition_col).distinct().collect()}


# ── 1. KRX 상장종목 수집 (JSON) ─────────────────────────────────────────
def fetch_krx_listed(stock_api_key: str, bas_dt: str) -> list[dict]:
    """KRX 상장종목 정보 조회 (KOSPI+KOSDAQ), DartCompanyCollector.collectKrxStockInfo와 동일 로직"""
    params = {
        "serviceKey": stock_api_key,
        "numOfRows": 4000,  # 전체 종목 한 번에 수신
        "pageNo": 1,
        "resultType": "json",
        "basDt": bas_dt,
    }
    body = request_json_with_retry(KRX_LISTED_URL, params)
    items = body.get("response", {}).get("body", {}).get("items", {})
    item_list = items.get("item", []) if items else []

    result = []
    for item in item_list:
        mrkt_ctg = (item.get("mrktCtg") or "").strip()
        if mrkt_ctg not in ("KOSPI", "KOSDAQ"):  # KONEX 등 제외
            continue

        corp_name = (item.get("itmsNm") or "").strip()
        # 스팩/리츠 제외 (DartCompanyCollector.isExcludedStock과 동일 조건)
        if corp_name.endswith("리츠") or "스팩" in corp_name or "기업인수목적" in corp_name:
            continue

        srtn_cd = (item.get("srtnCd") or "").strip().lstrip("A")  # KRX 종목코드 앞의 'A' 접두사 제거
        if not srtn_cd:
            continue

        result.append({
            "stock_code": srtn_cd,
            "corp_name": corp_name,
            "corp_cls": "Y" if mrkt_ctg == "KOSPI" else "K",  # KOSPI->Y, KOSDAQ->K
        })
    return result


# ── 2. DART corpCode.xml 수집 (ZIP 안 XML - DART 자체 스펙상 이 응답만 XML) ──
def fetch_dart_corp_code_map(dart_api_key: str, listed_stock_codes: set[str]) -> dict[str, str]:
    """DART corpCode.xml에서 KRX 상장 종목에 해당하는 stock_code -> corp_code 맵 추출 (DartCompanyCollector.collectCorpCodeMap과 동일)"""
    resp = requests.get(DART_CORP_CODE_URL, params={"crtfc_key": dart_api_key}, timeout=30)
    resp.raise_for_status()

    with zipfile.ZipFile(io.BytesIO(resp.content)) as zf:
        xml_bytes = zf.read(zf.namelist()[0])

    root = ElementTree.fromstring(xml_bytes)
    stock_to_corp = {}
    for el in root.iter("list"):
        stock_code = (el.findtext("stock_code") or "").strip()
        if not stock_code or stock_code not in listed_stock_codes:
            continue
        corp_code = (el.findtext("corp_code") or "").strip()
        stock_to_corp[stock_code] = corp_code
    return stock_to_corp


# ── 3. 주가 수집 (JSON, 기준일 1회 호출로 전 종목 수신) ─────────────────────
def fetch_stock_prices(stock_api_key: str, target_date: date, stock_codes: set[str]) -> list[dict]:
    """기준일(basDt) 1회 호출로 전 종목 응답 수신 후 Company 대상 종목만 필터링 (StockPriceCollector.collectByDate와 동일)"""
    bas_dt = target_date.strftime("%Y%m%d")
    params = {
        "serviceKey": stock_api_key,
        "numOfRows": 4000,
        "pageNo": 1,
        "resultType": "json",
        "basDt": bas_dt,
    }
    body = request_json_with_retry(STOCK_PRICE_URL, params)
    items = body.get("response", {}).get("body", {}).get("items", {})
    item_list = items.get("item", []) if items else []

    def to_num(value, cast):
        if value is None:
            return None
        try:
            return cast(str(value).replace(",", "").strip())
        except ValueError:
            return None

    result = []
    for item in item_list:
        srtn_cd = (item.get("srtnCd") or "").strip()
        bas_dt_str = item.get("basDt")
        if not bas_dt_str or srtn_cd not in stock_codes:
            continue

        result.append({
            "stock_code": srtn_cd,
            "bas_dt": bas_dt_str,
            "close_price": to_num(item.get("clpr"), int),        # 종가
            "open_price": to_num(item.get("mkp"), int),           # 시가
            "fluctuation_rate": to_num(item.get("fltRt"), float), # 등락률
            "listed_share_count": to_num(item.get("lstgStCnt"), int),   # 상장주식수
            "market_cap": to_num(item.get("mrktTotAmt"), int),    # 시가총액
        })
    return result


# ── 3-1. 특정일 이하 가장 최근 거래일 주가 조회 (모멘텀 계산용 스냅샷) ─────
# Java FinancialIndicatorService의 findTopBySrtnCdAndBasDtLessThanEqualOrderByBasDtDesc와 동일 의미:
# 정확히 그 날짜에 데이터가 없으면(주말/공휴일/거래정지) 하루씩 앞으로 가며 가장 가까운 이전 거래일을 찾는다.
MAX_LOOKBACK_DAYS = 10  # 연휴가 길어도 이전 거래일을 찾을 수 있도록 최대 탐색 범위


def fetch_stock_prices_on_or_before(stock_api_key: str, target_date: date, stock_codes: set[str]) -> tuple[list[dict], date | None]:
    """target_date 이하 가장 최근 거래일의 전 종목 시세와 실제 조회된 날짜를 함께 반환. 못 찾으면 ([], None)."""
    candidate = target_date
    for _ in range(MAX_LOOKBACK_DAYS):
        day_prices = fetch_stock_prices(stock_api_key, candidate, stock_codes)
        if day_prices:
            return day_prices, candidate
        candidate -= timedelta(days=1)
    return [], None


# DART 재무제표/배당 API용 재시도 로직 - 어떤 예외든 재시도, 대기시간 500ms*시도횟수로 증가 (DartFinancialCollector.requestWithRetry와 동일)
def request_dart_json_with_retry(url: str, params: dict, retry_count: int) -> dict | None:
    for i in range(retry_count):
        try:
            resp = requests.get(url, params=params, timeout=30)
            resp.raise_for_status()
            return resp.json()
        except Exception:
            time.sleep(0.5 * (i + 1))
    return None


# ── 4. DART 재무제표 수집 (JSON) ────────────────────────────────────────
def fetch_financial_statement(dart_api_key: str, corp_code: str, year: str, reprt_code: str, fs_div: str) -> list[dict] | None:
    """DART 단일회사 전체 재무제표 조회. CFS(연결) 우선, 없으면 OFS(별도)로 호출부에서 재시도 (DartFinancialCollector와 동일, 재시도 3회)"""
    params = {
        "crtfc_key": dart_api_key,
        "corp_code": corp_code,
        "bsns_year": year,
        "reprt_code": reprt_code,
        "fs_div": fs_div,
    }
    body = request_dart_json_with_retry(DART_FINANCIAL_URL, params, retry_count=3)

    # status="000" 정상, 그 외(예: 013=데이터 없음)는 해당 fs_div 재무제표 자체가 없는 경우
    if body is None or body.get("status") != "000" or not body.get("list"):
        return None
    return body["list"]


# ── 5. DART 배당 수집 (JSON) ────────────────────────────────────────────
def fetch_dividend(dart_api_key: str, corp_code: str, year: str, reprt_code: str) -> list[dict] | None:
    """DART 배당에 관한 사항 API 조회 (DividendCollector.collect와 동일, 재시도 1회)"""
    params = {
        "crtfc_key": dart_api_key,
        "corp_code": corp_code,
        "bsns_year": year,
        "reprt_code": reprt_code,
    }
    body = request_dart_json_with_retry(DART_DIVIDEND_URL, params, retry_count=1)

    if body is None or body.get("status") != "000" or not body.get("list"):
        return None
    return body["list"]


def main():
    parser = argparse.ArgumentParser(description="01_ingest_raw: 원천 API -> Parquet 변환")
    parser.add_argument("--bas-dt", default=date.today().strftime("%Y%m%d"), help="기업/주가 기준일 (YYYYMMDD)")
    parser.add_argument("--year", required=True, help="재무제표/배당 수집 연도 (예: 2023)")
    parser.add_argument("--reprt-code", default="11011", help="보고서 코드 (11011=사업보고서)")
    parser.add_argument("--price-start", help="주가 수집 시작일 (YYYYMMDD), 미지정 시 --bas-dt와 동일")
    parser.add_argument("--price-end", help="주가 수집 종료일 (YYYYMMDD), 미지정 시 --bas-dt와 동일")
    parser.add_argument("--data-dir", default="/opt/spark-apps/data/raw", help="Parquet 출력 루트 경로")
    parser.add_argument("--dart-limit", type=int, default=None,
                         help="DART 재무제표/배당 수집 종목 수 제한 (종목당 개별 호출이라 DART 콜량에 직결). "
                              "KRX/주가는 공공데이터포털이 기준일 1회 호출로 전 종목을 주므로 이 옵션의 영향을 받지 않음. "
                              "미지정 시 전체 상장 종목 대상 (DART 일일 10,000콜 제한 주의)")
    parser.add_argument("--dividends-only", action="store_true",
                         help="배당만 수집(주가 범위 수집 + 재무제표 수집 스킵). 재무제표는 3개년 스택으로 이미 확보됐는데 "
                              "배당(alotMatter API)만 연도별 단건이라 --year를 바꿔가며 추가 수집할 때, "
                              "재무제표까지 중복 호출되어 DART 콜을 낭비하는 것을 막기 위함")
    args = parser.parse_args()

    dart_api_key = os.environ["DART_API_KEY"]     # 하드코딩 금지 원칙 - 환경변수로만 주입
    stock_api_key = os.environ["STOCK_API_KEY"]

    spark = SparkSession.builder.appName("01_ingest_raw").getOrCreate()

    # ── 1단계: KRX 상장종목 + DART corpCode 매핑 ─────────────────────────
    krx_stocks = fetch_krx_listed(stock_api_key, args.bas_dt)
    print(f"KRX 상장 종목 수집 완료: {len(krx_stocks)}건")

    stock_codes = {s["stock_code"] for s in krx_stocks}
    corp_code_map = fetch_dart_corp_code_map(dart_api_key, stock_codes)
    print(f"DART corpCode 매핑 완료: {len(corp_code_map)}건")

    companies = [
        {**s, "corp_code": corp_code_map[s["stock_code"]], "bas_dt": args.bas_dt}
        for s in krx_stocks if s["stock_code"] in corp_code_map
    ]

    companies_path = f"{args.data_dir}/companies"
    spark.createDataFrame(companies).write.mode("overwrite") \
        .partitionBy("bas_dt").parquet(companies_path)
    print(f"companies 저장 완료: {companies_path}")

    dart_targets = companies[:args.dart_limit] if args.dart_limit is not None else companies
    if args.dart_limit is not None:
        print(f"--dart-limit 적용: DART 재무제표/배당은 {len(dart_targets)}종목만 수집 (콜량 절약)")

    if not args.dividends_only:
        # ── 2단계: 주가 수집 (일자 범위, 종목/연도 기준 파티셔닝) ─────────────
        price_start = datetime.strptime(args.price_start or args.bas_dt, "%Y%m%d").date()
        price_end = datetime.strptime(args.price_end or args.bas_dt, "%Y%m%d").date()

        prices_path = f"{args.data_dir}/prices"
        # 날짜 범위 루프 시작 전 기존 bas_dt 집합을 한 번만 조회 (매 반복마다 전체 parquet 재스캔 방지)
        ingested_bas_dts = already_ingested_values(spark, prices_path, "bas_dt")
        all_prices = []
        cur = price_start
        while cur <= price_end:
            year_val = str(cur.year)
            # 이미 받은 연도/기준일이면 스킵 (DART 콜 제한과 무관하지만 공공데이터포털도 트래픽 절약 차원에서 동일 원칙 적용)
            if cur.strftime("%Y%m%d") in ingested_bas_dts:
                print(f"이미 수집된 날짜, 스킵: {cur}")
                cur += timedelta(days=1)
                continue

            day_prices = fetch_stock_prices(stock_api_key, cur, stock_codes)
            for p in day_prices:
                p["year"] = year_val
                p["snapshot_type"] = "current"  # 03_build_indicators.py에서 stack() 대신 이 컬럼으로 시점 구분
            all_prices.extend(day_prices)
            print(f"주가 수집 완료: {cur} {len(day_prices)}건")
            cur += timedelta(days=1)

        # ── 2-1단계: 모멘텀/F-Score(신주발행 여부) 계산용 1개월전·12개월전 스냅샷 ──
        # FinancialIndicatorService.resolveMomentum과 동일하게 "기준일(가장 최근 수집일) 하루"에 대해서만
        # 조회한다. 04_backtest_grid.py처럼 여러 리밸런싱 시점마다 모멘텀이 필요한 경우는 그 단계에서 별도 처리.
        momentum_base_dt = price_end
        for label, months_back in (("1m_ago", 1), ("12m_ago", 12)):
            target = months_ago(momentum_base_dt, months_back)
            if snapshot_type_exists(spark, prices_path, label):
                print(f"{label} 스냅샷 이미 존재, 스킵 (최신 스냅샷 하나면 충분하므로 재수집 안 함)")
                continue

            snapshot_prices, actual_date = fetch_stock_prices_on_or_before(stock_api_key, target, stock_codes)
            if not snapshot_prices:
                print(f"{label} 스냅샷 조회 실패 (기준일 {target}로부터 {MAX_LOOKBACK_DAYS}일 이내 거래일 없음)")
                continue

            for p in snapshot_prices:
                p["year"] = str(actual_date.year)
                p["snapshot_type"] = label
            all_prices.extend(snapshot_prices)
            print(f"{label} 스냅샷 수집 완료: 목표 {target} -> 실제 {actual_date} {len(snapshot_prices)}건")

        if all_prices:
            spark.createDataFrame(all_prices).write.mode("append") \
                .partitionBy("year").parquet(prices_path)
            print(f"prices 저장 완료: {prices_path}")

        # ── 3단계: DART 재무제표 수집 (종목별, 연도 파티셔닝) ─────────────────
        financials_path = f"{args.data_dir}/financials"
        if already_ingested(spark, financials_path, args.year, "year"):
            print(f"이미 수집된 연도, 재무제표 스킵: {args.year}")
        else:
            financial_rows = []
            for company in dart_targets:
                try:
                    # CFS(연결) 먼저 시도, 없으면 OFS(별도)로 재시도 - DartFinancialCollector와 동일 순서
                    items = fetch_financial_statement(dart_api_key, company["corp_code"], args.year, args.reprt_code, "CFS")
                    fs_div = "CFS"
                    if items is None:
                        time.sleep(SLEEP_SEC)
                        items = fetch_financial_statement(dart_api_key, company["corp_code"], args.year, args.reprt_code, "OFS")
                        fs_div = "OFS"

                    if items is None:
                        print(f"재무데이터 없음(CFS/OFS 모두): {company['corp_name']}")
                        continue

                    for it in items:
                        it["stock_code"] = company["stock_code"]
                        it["fs_div"] = fs_div
                        it["year"] = args.year
                    financial_rows.extend(items)
                    time.sleep(SLEEP_SEC)  # DART API 호출 간격 유지 (차단 방지)

                except Exception as e:
                    print(f"재무제표 수집 실패: {company['corp_name']} - {e}")

            if financial_rows:
                spark.createDataFrame(financial_rows).write.mode("append") \
                    .partitionBy("year").parquet(financials_path)
                print(f"financials 저장 완료: {financials_path} ({len(financial_rows)}건)")
    else:
        print("--dividends-only 지정됨: 주가 수집/재무제표 수집 스킵, 배당만 진행")

    # ── 4단계: DART 배당 수집 (종목별, 연도 파티셔닝) ─────────────────────
    dividends_path = f"{args.data_dir}/dividends"
    if already_ingested(spark, dividends_path, args.year, "year"):
        print(f"이미 수집된 연도, 배당 스킵: {args.year}")
    else:
        dividend_rows = []
        for company in dart_targets:
            try:
                items = fetch_dividend(dart_api_key, company["corp_code"], args.year, args.reprt_code)
                if items is None:
                    print(f"배당 데이터 없음: {company['corp_name']}")
                    continue

                for it in items:
                    it["stock_code"] = company["stock_code"]
                    it["year"] = args.year
                dividend_rows.extend(items)
                time.sleep(SLEEP_SEC)

            except Exception as e:
                print(f"배당 수집 실패: {company['corp_name']} - {e}")

        if dividend_rows:
            spark.createDataFrame(dividend_rows).write.mode("append") \
                .partitionBy("year").parquet(dividends_path)
            print(f"dividends 저장 완료: {dividends_path} ({len(dividend_rows)}건)")

    spark.stop()


if __name__ == "__main__":
    main()
