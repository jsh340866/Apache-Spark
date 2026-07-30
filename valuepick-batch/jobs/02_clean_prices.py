"""
02_clean_prices.py - 시세 정제 (PROJECT_INSTRUCTIONS.md 4.2)

역할: data/raw/prices의 원본 시세를 종목별로 정제한다.
  - 결측 거래일 보간: 종목별 거래일 캘린더 대비 비어있는 날짜를 직전 종가로 forward-fill
    (거래정지 구간도 원본에 시세 자체가 없는 형태로 들어오므로 이 로직으로 함께 처리됨.
     보간된 행은 is_interpolated=true로 표시해 이후 단계에서 구분 가능하게 한다.)
    * snapshot_type="current"(백테스트 대상 구간) 행만 정제 대상으로 삼는다.
      01_ingest_raw.py가 모멘텀 계산용으로 별도 수집한 1m_ago/12m_ago 스냅샷은 시점이 뚝 떨어져 있어
      current 구간의 거래일 캘린더에 섞으면 그 사이 날짜가 전부 "결측"으로 오인되어 잘못 보간되므로 제외하고,
      원본 그대로 통과시킨다.
  - 액면분할/병합 의심 탐지: 전일 대비 종가 등락률이 임계치를 벗어나는 지점을 split_suspected로 표시만 한다.
    (원천 API 응답에 분할 이벤트 필드가 없어 확정 판정이 불가능하므로, 자동으로 주가를 보정하지 않고
     휴리스틱 탐지 결과만 남겨 후속 분석/수동 검증에 활용한다. 잘못된 자동 보정으로 인한 오탐지 위험을 피하기 위함.)

왜 Spark인가: 종목별로 독립적인 정제 작업이 전체 상장 종목(약 2,500개)에 걸쳐 반복된다.
             Window.partitionBy("stock_code")로 종목 단위 병렬화가 자연스럽게 이루어진다.

입력: data/raw/prices (year 파티셔닝)
출력: data/cleaned/prices (year 파티셔닝)
"""

from __future__ import annotations

import argparse

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F
from pyspark.sql.window import Window

# 액면분할/병합 의심 판정 임계값 - 전일 대비 종가 등락률이 이 범위를 벗어나면 플래그
# (거래정지 해제 직후 상한가/하한가 급등락과 구분하기 위해 일반적인 가격제한폭 ±30%보다 넉넉하게 잡음)
SPLIT_SUSPECT_DROP_RATE = -0.40   # 전일 대비 -40% 이하 (예: 액면분할로 주가가 1/N로 조정)
SPLIT_SUSPECT_SURGE_RATE = 0.67   # 전일 대비 +67% 이상 (예: 액면병합으로 주가가 N배로 조정)


def build_trading_calendar(prices: DataFrame) -> DataFrame:
    """전체 종목 시세에 실제로 등장한 거래일자 목록 (특정 종목 하루만 결측이어도 캘린더 자체는 존재해야 보간 가능)"""
    return prices.select("bas_dt").distinct()


def fill_missing_trading_days(prices: DataFrame, calendar: DataFrame) -> DataFrame:
    """종목 x 거래일 전체 조합을 만들고, 원본에 없는 날짜는 직전 종가로 forward-fill (거래정지 구간 포함)"""
    stock_codes = prices.select("stock_code").distinct()
    full_grid = stock_codes.crossJoin(calendar)

    joined = full_grid.join(prices, on=["stock_code", "bas_dt"], how="left") \
        .withColumn("is_interpolated", F.col("close_price").isNull())

    # 직전 유효값을 그대로 끌어오기 위한 정렬 윈도우 (파티션: 종목, 정렬: 거래일)
    w = Window.partitionBy("stock_code").orderBy("bas_dt").rowsBetween(Window.unboundedPreceding, 0)

    filled = joined
    for col in ["close_price", "open_price", "listed_share_count", "market_cap"]:
        # 결측 구간 안에서는 last(컬럼, ignorenulls=True)로 직전 유효값을 계속 끌어옴
        filled = filled.withColumn(col, F.last(col, ignorenulls=True).over(w))

    # 보간된 행은 등락률 자체가 의미 없으므로(직전 종가와 동일) 0으로 채움
    filled = filled.withColumn(
        "fluctuation_rate",
        F.when(F.col("is_interpolated"), F.lit(0.0)).otherwise(F.col("fluctuation_rate")),
    )
    return filled


def flag_split_suspects(prices: DataFrame) -> DataFrame:
    """종목별 전일 종가 대비 등락률이 임계치를 벗어나면 split_suspected=true로 표시 (자동 보정 없음)"""
    w = Window.partitionBy("stock_code").orderBy("bas_dt")
    prev_close = F.lag("close_price").over(w)

    day_over_day_rate = (F.col("close_price") - prev_close) / prev_close
    return prices.withColumn(
        "split_suspected",
        F.when(prev_close.isNull(), F.lit(False))
         .when(day_over_day_rate <= SPLIT_SUSPECT_DROP_RATE, F.lit(True))
         .when(day_over_day_rate >= SPLIT_SUSPECT_SURGE_RATE, F.lit(True))
         .otherwise(F.lit(False)),
    )


def main():
    parser = argparse.ArgumentParser(description="02_clean_prices: 원본 시세 정제 (결측 보간 + 액면분할 의심 탐지)")
    parser.add_argument("--input-dir", default="/opt/spark-apps/data/raw/prices", help="원본 시세 Parquet 경로")
    parser.add_argument("--output-dir", default="/opt/spark-apps/data/cleaned/prices", help="정제 결과 Parquet 출력 경로")
    args = parser.parse_args()

    spark = SparkSession.builder.appName("02_clean_prices").getOrCreate()

    raw = spark.read.parquet(args.input_dir)
    print(f"원본 시세 로드 완료: {raw.count()}건")

    # 모멘텀 계산용 스냅샷(1m_ago/12m_ago)은 정제 파이프라인(보간/분할탐지) 대상이 아님 - 원본 그대로 통과
    prices = raw.filter(F.col("snapshot_type") == "current")
    snapshots = raw.filter(F.col("snapshot_type") != "current") \
        .withColumn("is_interpolated", F.lit(False)) \
        .withColumn("split_suspected", F.lit(False))
    print(f"정제 대상(current) {prices.count()}건 / 스냅샷(그대로 통과) {snapshots.count()}건")

    calendar = build_trading_calendar(prices)
    filled = fill_missing_trading_days(prices, calendar)
    interpolated_count = filled.filter(F.col("is_interpolated")).count()
    print(f"결측 거래일 보간 완료: {interpolated_count}건 보간됨")

    # 관측 기간 내내 시세가 한 번도 없던 종목(직전 유효값 자체가 없어 forward-fill이 못 채운 행) 제거.
    # 예: 수집 시작일 이전에 상장된 이력이 없는 신규 상장 종목. 이런 행은 close_price가 NULL로 남으므로
    # 이후 지표 계산(03단계)에서 어차피 사용할 수 없어 이 단계에서 걸러낸다.
    no_prior_data_count = filled.filter(F.col("is_interpolated") & F.col("close_price").isNull()).count()
    if no_prior_data_count > 0:
        print(f"직전 유효값 없어 보간 불가한 행 제거: {no_prior_data_count}건")
        filled = filled.filter(~(F.col("is_interpolated") & F.col("close_price").isNull()))

    flagged = flag_split_suspects(filled)
    suspect_count = flagged.filter(F.col("split_suspected")).count()
    print(f"액면분할/병합 의심 탐지 완료: {suspect_count}건 플래그")

    # year 파티션 컬럼은 bas_dt 기준으로 재계산 (crossJoin 과정에서 원본 year 값이 결측 행에 비어있으므로)
    cleaned_current = flagged.withColumn("year", F.col("bas_dt").substr(1, 4))

    # 스냅샷은 정제 대상이 아니었으므로 그대로 합침 (컬럼 순서를 cleaned_current에 맞춤)
    snapshots_aligned = snapshots.select(*cleaned_current.columns)
    result = cleaned_current.unionByName(snapshots_aligned)

    result.write.mode("overwrite").partitionBy("year").parquet(args.output_dir)
    print(f"cleaned prices 저장 완료: {args.output_dir} ({result.count()}건)")

    spark.stop()


if __name__ == "__main__":
    main()
