"""
_seed_valuepick_db.py - verify_indicators 검증 전용 (파이프라인 구성요소 아님)

역할: 01_ingest_raw.py가 이미 받아온 Parquet 원본을 ValuePick(Spring Boot) 로컬 DB 스키마에 맞춰
그대로 역주입한다. 목적은 03_build_indicators.py의 계산값을 실제 FinancialIndicatorService.calculate()가
계산한 값과 대조하는 것 - 추가 API 호출 없이, 정확히 동일한 입력값으로 비교하기 위해 이 방식을 쓴다.

주의: financials pivot 로직(account_id/account_nm 매칭)은 03_build_indicators.py와 동일해야
"같은 해석의 입력값"으로 비교가 성립한다. 로직을 바꾸면 여기도 같이 바꿀 것.

실행: spark-submit --properties-file /opt/spark-apps/conf/spark-defaults.conf \
      --jars /opt/spark/jars/mysql-connector-j-8.3.0.jar \
      /opt/spark-apps/jobs/_seed_valuepick_db.py --year 2023
"""

from __future__ import annotations

import argparse
import sys
import os

sys.path.insert(0, os.path.dirname(__file__))
from importlib import import_module

build_indicators = import_module("03_build_indicators")

from pyspark.sql import SparkSession
from pyspark.sql import functions as F

MYSQL_URL = "jdbc:mysql://db:3306/valuepick?serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
MYSQL_PROPS = {
    "user": "valuepick",
    "password": "1234",
    "driver": "com.mysql.cj.jdbc.Driver",
}


def write_jdbc(df, table: str, mode: str = "append"):
    df.write.jdbc(url=MYSQL_URL, table=table, mode=mode, properties=MYSQL_PROPS)


def main():
    parser = argparse.ArgumentParser(description="_seed_valuepick_db: Parquet -> ValuePick MySQL 역주입 (검증 전용)")
    parser.add_argument("--year", required=True)
    parser.add_argument("--data-dir", default="/opt/spark-apps/data/raw")
    args = parser.parse_args()

    spark = SparkSession.builder.appName("_seed_valuepick_db").getOrCreate()

    # ── COMPANY ──────────────────────────────────────────────────────────
    companies = spark.read.parquet(f"{args.data_dir}/companies")
    company_rows = companies.select(
        F.col("stock_code"),
        F.col("corp_code"),
        F.col("corp_name"),
        F.col("corp_cls"),
        F.lit(None).cast("string").alias("induty_code"),  # 01_ingest_raw.py는 업종코드를 수집하지 않음
        F.lit(None).cast("string").alias("induty_nm"),
        F.lit(None).cast("string").alias("ceo_nm"),
        F.current_timestamp().alias("created_at"),
        F.current_timestamp().alias("updated_at"),
    )
    write_jdbc(company_rows, "COMPANY")
    print(f"COMPANY 적재 완료: {company_rows.count()}건")

    # ── STOCK_PRICE (current 스냅샷만 - Java의 findTopBySrtnCdOrderByBasDtDesc가 최신값만 씀) ──
    prices = spark.read.parquet(f"{args.data_dir}/../cleaned/prices") \
        if os.path.exists(f"{args.data_dir}/../cleaned/prices") else spark.read.parquet(f"{args.data_dir}/prices")
    stock_price_rows = prices.select(
        F.col("stock_code").alias("srtn_cd"),
        F.to_date(F.col("bas_dt"), "yyyyMMdd").alias("bas_dt"),
        F.col("close_price").cast("long").alias("clpr"),
        F.col("listed_share_count").cast("long").alias("lstg_st_cnt"),
        F.col("market_cap").cast("long").alias("mrkt_tot_amt"),
        F.current_timestamp().alias("created_at"),
        F.current_timestamp().alias("updated_at"),
        F.col("open_price").cast("long").alias("mkp"),
        F.col("fluctuation_rate").cast("double").alias("flt_rt"),
    )
    write_jdbc(stock_price_rows, "STOCK_PRICE")
    print(f"STOCK_PRICE 적재 완료: {stock_price_rows.count()}건")

    # ── FINANCIAL_STATEMENT (03_build_indicators.py와 동일한 pivot 로직 재사용) ────
    fin_raw = spark.read.parquet(f"{args.data_dir}/financials")
    stacked = build_indicators.stack_financial_years(fin_raw)
    pivoted = build_indicators.pivot_financials(stacked)

    fin_rows = pivoted.filter(F.col("year") == str(args.year)).select(
        F.col("year").alias("bsns_year"),
        F.col("stock_code"),
        F.lit("11011").alias("reprt_code"),
        F.col("fs_div"),
        "revenue", "operating_income", "net_income", "total_assets", "total_liabilities",
        "total_equity", "current_assets", "current_liabilities", "operating_cash_flow", "gross_profit",
        "currency",
    )
    write_jdbc(fin_rows, "FINANCIAL_STATEMENT")
    print(f"FINANCIAL_STATEMENT 적재 완료 ({args.year}년): {fin_rows.count()}건")

    # ── DIVIDEND_INFO (PK가 corp_code+dividend_kind뿐이라 종목당 1행만 - '주당 현금배당금(원)' x '보통주'만) ──
    # dividends 원본에 이미 corp_code가 있으므로(fetch_dividend가 corp_code 기준으로 조회) 별도 join 불필요
    dividends = spark.read.parquet(f"{args.data_dir}/dividends")
    div_common = dividends.filter(
        (F.col("se") == "주당 현금배당금(원)") & (F.col("stock_knd") == "보통주")
    )

    dividend_rows = div_common.select(
        F.col("corp_code"),
        F.lit("보통주").alias("dividend_kind"),
        F.regexp_replace(F.trim(F.col("thstrm")), ",", "").cast("long").alias("dividend_amount"),
        F.to_timestamp(F.col("stlm_dt")).alias("stlm_dt"),
    ).dropDuplicates(["corp_code", "dividend_kind"])
    write_jdbc(dividend_rows, "DIVIDEND_INFO")
    print(f"DIVIDEND_INFO 적재 완료: {dividend_rows.count()}건")

    spark.stop()


if __name__ == "__main__":
    main()
