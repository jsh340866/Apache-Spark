"""
05_export_to_mysql.py - 백테스트 결과 MySQL 적재 (파이프라인 마지막 단계)

역할: 04_backtest_grid.py가 산출한 백테스트 결과 Parquet을 Spark JDBC writer로 MySQL에 적재한다.
CLAUDE.md 8항 원칙("MySQL을 Spark 잡 간 중간 데이터 전달 용도로 쓰지 않는다, 전부 Parquet")의
유일한 예외가 이 잡이다 - 01~04번 사이는 전부 Parquet으로만 데이터를 주고받았고, 05번은 그 최종
산출물을 "서빙"하기 위해서만 MySQL을 쓴다. 여기서 만드는 두 테이블은 기존 ValuePick(Spring Boot)
프론트엔드/API가 조회할 대상이며, 이 잡이 그 Entity/스케줄러를 직접 건드리지는 않는다(절대 원칙 1항).

적재 방식: --market(ALL/KOSPI/KOSDAQ)별로 테이블 자체를 분리한다
(strategy_performance_all/kospi/kosdaq, backtest_results_all/kospi/kosdaq). 같은 market으로
재실행하면 그 market의 테이블만 truncate 후 전체 재삽입한다(01_ingest_raw.py의 companies가
mode("overwrite")인 것과 같은 결) - 다른 market의 테이블은 영향받지 않는다. 즉 market별 테이블은
서로 독립적으로 유지되지만, 같은 market 안에서는 run_id별 이력을 누적 보관하지 않고 최신 실행
결과로만 덮어써진다(이력 관리는 현재 요구사항 밖이라 과설계로 판단해 제외했다).
market 컬럼은 테이블명이 이미 market을 구분함에도 그대로 유지한다 - 테이블 안에서도 어떤 market
결과인지 바로 보이게 하기 위함이다(04번 출력 Parquet 자체에는 이 컬럼이 없음 - 04번이 --market으로
이미 필터링해서 산출한 결과이므로 그 값을 그대로 꼬리표처럼 붙이는 것).

입력:
  - {input-dir}/summary (04번 산출, 전략별 1행) -> MySQL strategy_performance_{market} 테이블
  - {input-dir}/period_returns (04번 산출, 전략x구간별 1행) -> MySQL backtest_results_{market} 테이블
출력: MySQL valuepick_backtest DB의 strategy_performance_{market} / backtest_results_{market}
테이블 (해당 market 테이블만 truncate 후 재삽입)

실행 예시 (spark-master 컨테이너 안에서):
  docker exec spark-master spark-submit \\
    --properties-file /opt/spark-apps/conf/spark-defaults.conf \\
    /opt/spark-apps/jobs/05_export_to_mysql.py --market ALL
"""

from __future__ import annotations

import argparse
import os

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F


def jdbc_write(df: DataFrame, table: str, url: str, password: str) -> None:
    """summary/period_returns 공통 JDBC 적재 로직. table 인자는 market별로 분리된 테이블명
    (예: strategy_performance_all)이 그대로 전달되므로, 이 함수는 market을 알 필요가 없다.
    mode("overwrite")는 Spark JDBC writer 기본 동작이 DROP TABLE 후 Spark 추론 타입으로 테이블을
    재생성하는 것이라, 매 실행마다 컬럼 타입이 흔들릴 수 있어 서빙용 DB에는 부적합하다. truncate
    옵션을 함께 주면 "기존 테이블은 그대로 두고 데이터만 비운 뒤 재삽입"으로 동작이 바뀐다(단,
    테이블이 아직 없는 최초 실행에서는 truncate할 대상이 없으므로 이 옵션이 무시되고 Spark가
    자동으로 테이블을 만든다 - JDBC writer 자체의 동작이며, 이 잡이 별도로 분기 처리하지 않는다).
    테이블명이 market별로 분리되어 있으므로, 이 truncate는 해당 market 테이블에만 적용되고
    다른 market의 테이블은 건드리지 않는다."""
    df.write.format("jdbc") \
        .option("url", url) \
        .option("dbtable", table) \
        .option("user", "root") \
        .option("password", password) \
        .option("truncate", "true") \
        .mode("overwrite") \
        .save()


def main():
    parser = argparse.ArgumentParser(description="05_export_to_mysql: 백테스트 결과 MySQL 적재")
    parser.add_argument("--input-dir", default="/opt/spark-apps/data/backtest_results",
                         help="04_backtest_grid.py --output-dir과 동일한 경로. 그 아래 summary/period_returns를 읽는다")
    parser.add_argument("--market", required=True, choices=["ALL", "KOSPI", "KOSDAQ"],
                         help="이번 04번 실행에 쓰인 --market 값을 그대로 지정. 모든 적재 행에 market 컬럼으로 채워짐"
                              " (04번 출력 Parquet 자체에는 없는 값이라 실수로 누락되면 안 되므로 필수 인자)")
    parser.add_argument("--mysql-host", default="mysql", help="Docker Compose 서비스명 (spark-net 네트워크 내부 DNS)")
    parser.add_argument("--mysql-port", default="3306", help="컨테이너 내부 포트 (호스트 노출 포트 3307과 다름)")
    parser.add_argument("--mysql-database", default="valuepick_backtest")
    args = parser.parse_args()

    # 하드코딩 금지 원칙 - 비밀번호는 환경변수로만 주입. docker-compose.yml의 mysql 서비스와
    # 동일한 MYSQL_ROOT_PASSWORD를 spark-master 컨테이너 environment에도 주입해서 읽는다.
    mysql_password = os.environ["MYSQL_ROOT_PASSWORD"]
    jdbc_url = f"jdbc:mysql://{args.mysql_host}:{args.mysql_port}/{args.mysql_database}"

    spark = SparkSession.builder.appName("05_export_to_mysql").getOrCreate()

    summary = spark.read.parquet(f"{args.input_dir}/summary").withColumn("market", F.lit(args.market))
    period_returns = spark.read.parquet(f"{args.input_dir}/period_returns").withColumn("market", F.lit(args.market))

    # 테이블명 자체를 market별로 분리 - 나중에 다른 market으로 재실행해도 이전 market의 테이블을
    # truncate하지 않는다 (예: strategy_performance_all, strategy_performance_kospi)
    market_suffix = args.market.lower()
    summary_table = f"strategy_performance_{market_suffix}"
    period_returns_table = f"backtest_results_{market_suffix}"

    jdbc_write(summary, summary_table, jdbc_url, mysql_password)
    print(f"{summary_table} 적재 완료: {summary.count()}행 (market={args.market})")

    jdbc_write(period_returns, period_returns_table, jdbc_url, mysql_password)
    print(f"{period_returns_table} 적재 완료: {period_returns.count()}행 (market={args.market})")

    spark.stop()


if __name__ == "__main__":
    main()
