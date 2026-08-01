# ARCHITECTURE.md — valuepick-batch 파이프라인 구조

이 문서는 valuepick-batch가 실제로 어떤 구조로 구현돼 있는지를 다룬다. 검증 이력은
`VALIDATION.md`, 성능 실측치는 `PERFORMANCE.md` 참고.

## 1. 전체 파이프라인 개요

```
01_ingest_raw.py  ->  02_clean_prices.py  ->  03_build_indicators.py  ->  04_backtest_grid.py  ->  05_export_to_mysql.py
   (원천 API)          (시세 정제)             (지표 계산)              (전략 그리드 백테스트)      (MySQL 서빙)
     Parquet              Parquet                  Parquet                   Parquet                  MySQL
```

잡 사이의 데이터 전달은 05번을 제외하면 전부 Parquet이다(각 잡의 `--input-dir`/`--output-dir`
인자로 경로를 연결). 05번이 MySQL을 쓰는 이유는 6절 참고.

### 01_ingest_raw.py — 데이터 레이크 구축

KRX/DART/공공데이터포털 API 응답을 원본 그대로 Parquet으로 저장한다. 기존 ValuePick
(Spring Boot)의 `StockPriceCollector`/`DartCompanyCollector`/`DartFinancialCollector`/
`DividendCollector`는 엔드포인트·인증·파싱 로직만 참고하고, MySQL 저장 로직은 쓰지 않는다.

- 출력: `data/raw/companies`(bas_dt 파티션, overwrite), `data/raw/prices`(year 파티션, append),
  `data/raw/financials`(year 파티션, append), `data/raw/dividends`(year 파티션, append)
- `companies`에는 KOSPI/KOSDAQ 종목 목록 + DART `corp_code` + `induty_code`(업종코드, F-Score
  금융업 예외 판정용)가 들어간다.
- `prices`는 `snapshot_type` 컬럼으로 `current`(백테스트 대상 일별 시세)와 `1m_ago`/`12m_ago`
  (모멘텀 계산용 스냅샷)를 구분한다.
- API 키는 `DART_API_KEY`/`STOCK_API_KEY`로만 주입.

### 02_clean_prices.py — 시세 정제

`data/raw/prices`의 `snapshot_type="current"` 구간만 정제 대상으로 삼는다(1m_ago/12m_ago는
시점이 뚝 떨어져 있어 같이 처리하면 그 사이가 전부 결측으로 오인되므로 원본 그대로 통과).

- 결측 거래일: 종목×전체 거래일 조합을 만들고(`crossJoin`), 없는 날짜는 직전 종가로
  forward-fill한 뒤 `is_interpolated` 플래그를 남긴다.
- 액면분할/병합 의심: 전일 대비 등락률이 -40% 이하 또는 +67% 이상이면 `split_suspected`로
  표시만 한다. 원천 API에 분할 이벤트 필드가 없어 확정 판정이 불가능하므로, 자동 보정 없이
  플래그만 남기고 이후 단계가 참조 여부를 결정한다.
- 출력: `data/cleaned/prices` (year 파티션)

### 03_build_indicators.py — 가치투자 지표 계산

기존 ValuePick `FinancialIndicatorService`/`DartFinancialCollector`의 계산·매핑 로직을
재현해 EPS/BPS/PER/PBR/ROE/부채비율/배당수익률/ROA/모멘텀/F-Score/EPS성장률을 계산한다.

- 입력: `data/raw/financials`(계정 단위 원본), `data/cleaned/prices`, `data/raw/dividends`,
  `data/raw/companies`
- DART 재무제표 응답 1건에 이미 당기(thstrm)/전기(frmtrm)/전전기(bfefrmtrm) 3개년이 들어있어
  `stack()`으로 언피벗한 뒤 `account_id` 우선/`account_nm` 차선 + `sj_div`(BS/CIS/IS/CF)
  필터링으로 계정을 매칭한다.
- 한국수출입은행 API로 실시간 환율을 조회해 외화 재무제표를 KRW로 환산한다(`EXIM_API_KEY`).
- 출력에는 `rcept_no`(DART 접수번호, 앞 8자리가 실제 공시일 YYYYMMDD)를 반드시 포함한다 —
  04번이 룩어헤드 바이어스를 막기 위해 조인하는 키다.
- 출력: `data/indicators` (year 파티션)

### 04_backtest_grid.py — 전략 그리드 백테스트 (프로젝트 핵심)

`conf/strategies.yaml`에 정의된 전략(리밸런싱주기 × portfolio_size × 가중치프리셋) 각각에
대해, 매 리밸런싱 시점마다 종목을 스크리닝해 포트폴리오를 구성하고 다음 시점까지 보유했을
때의 수익률을 계산해 누적수익률/MDD/샤프비율을 산출한다.

- 입력: `conf/strategies.yaml`, `data/indicators`, `data/cleaned/prices`(snapshot_type=current),
  `data/raw/companies`(induty_code 포함)
- 출력: `{output-dir}/summary`(전략별 1행), `{output-dir}/period_returns`(전략×구간별 1행)
- 룩어헤드 바이어스 방지: `rcept_no <= rebalance_date` 조건으로, 리밸런싱 시점 t에는 그 이전에
  실제로 공시된 재무제표만 사용한다.
- 종목 선정 방식: 2026-07-31 세션에 PER/PBR/배당수익률 문턱값(threshold) 그리드에서 ValuePick
  `Top100Service.scoreAll()`과 동일한 7팩터 백분위 가중합산 점수 방식으로 전환했다. 배경과
  구조는 4절 참고. 기존 threshold 버전은 `jobs/04_backtest_grid_threshold.py`로 보존돼 있다.

### 05_export_to_mysql.py — MySQL 서빙

04번 출력(summary/period_returns)을 Spark JDBC writer로 MySQL
`strategy_performance_{all|kospi}`/`backtest_results_{all|kospi}` 테이블(market별 분리)에
적재한다. 상세 배경은 6절.

## 2. Docker Compose 클러스터 구성

`docker/docker-compose.yml` 기준, 서비스별 역할:

| 서비스 | 역할 |
|---|---|
| `spark-master` | 클러스터 마스터. 잡 스케줄링·워커 리소스 배분. `spark-submit`도 이 컨테이너에서 실행(드라이버가 여기서 뜸). 8088(마스터 UI)·4040(드라이버/애플리케이션 UI)·7077(RPC) 포트 노출 |
| `spark-worker-1`, `spark-worker-2` | 실제 태스크(파티션 단위 작업) 실행 노드. 각각 2코어. 메모리는 실행 환경마다 다르게 설정(컴퓨터 A 2G / 컴퓨터 B 3G — `docs/ENVIRONMENT.md` 참고) |
| `spark-history` | 잡 종료 후에도 실행 이력을 조회하기 위한 History Server. 드라이버 UI(4040)는 잡이 죽으면 같이 사라지므로, `spark.eventLog.dir`에 쌓인 이벤트 로그를 읽어 사후 재구성한다(18080 포트). 도입 배경은 `PERFORMANCE.md` 참고 |
| `jupyter` | 검증·진단용 대화형 환경(PySpark 커널). `notebooks/*.ipynb` 실행 |
| `mysql` | 05번 최종 결과 서빙 전용. 호스트 포트 3307로 분리해 기존 ValuePick MySQL(3306)과 충돌 방지 |

모든 Spark 관련 서비스(`spark-master`/`spark-worker-*`/`spark-history`/`jupyter`)는
`../jobs`, `../conf`, `../data`, `../logs`를 동일한 절대경로(`/opt/spark-apps/...`)로
마운트한다. 분산 파일시스템이 없는 로컬 개발 환경에서 Spark Standalone 클러스터가 정상
동작하려면 모든 노드가 같은 경로에서 같은 파일을 볼 수 있어야 하기 때문이다.

## 3. 데이터 레이크 구조 (Parquet + year 파티셔닝)

```
data/
  raw/          companies, prices, financials, dividends   (year 또는 bas_dt 파티션)
  cleaned/      prices                                       (year 파티션)
  indicators/                                                 (year 파티션)
  backtest_results*/  summary, period_returns
```

- 잡 간 데이터 전달은 (05번을 제외하고) 전부 Parquet — CLAUDE.md/PROGRESS.md 8항 원칙에
  따라 MySQL을 중간 데이터 전달 용도로 쓰지 않는다.
- `year` 파티셔닝을 택한 이유: 03번(지표 계산)과 04번(백테스트)이 특정 연도만 골라 읽는
  경우가 많고(예: 03번은 `--year`로 그 해 사업보고서만 처리), 연도별 재실행 시
  `partitionOverwriteMode=dynamic`으로 그 해 파티션만 덮어써야 다른 연도 데이터가 보존된다.
  실제로 이 옵션을 빠뜨려 파티션 전체가 지워진 사고를 겪은 뒤 운영 규칙으로 고정됐다.

## 4. 04번의 벡터화 구조 — crossJoin + Window로 계획 조각을 1개로 고정

`prices_at_dates`, `latest_valid_indicators`, `screen_portfolio`, `calculate_period_returns`는
모두 같은 패턴을 쓴다: 리밸런싱 시점(또는 구간) 목록을 파이썬 for문으로 순회하지 않고,
그 목록 자체를 `spark.createDataFrame`으로 작은 DataFrame(`dates_df`/`periods_df`)으로
만들어 원본 데이터와 `crossJoin`한 뒤, `Window.partitionBy(..., "rebalance_date")`로 시점별
경계를 유지하면서 한 번에 처리한다.

```python
# prices_at_dates의 핵심 (jobs/04_backtest_grid.py)
dates_df = spark.createDataFrame([(d,) for d in rebalance_dates], ["rebalance_date"])
paired = current.crossJoin(F.broadcast(dates_df)) \
    .filter(F.col("bas_dt") <= F.col("rebalance_date"))
w = Window.partitionBy("stock_code", "rebalance_date").orderBy(F.desc("bas_dt"))
```

왜 이렇게 설계했는가: 원래 구조는 시점마다(monthly 36개, quarterly 12개) 함수를 호출해
결과를 `unionByName`으로 이어붙이는 방식이었다. 매 union마다 Catalyst 옵티마이저가 전체
실행계획(lineage)을 다시 분석해야 해서, 계획 길이가 시점 수보다 빠르게(초선형으로) 커졌다
(시점 1개→12개일 때 계획 길이가 정비례 대비 2.6배 더 늘어난 것을 실측). 이 계획 분석 비용은
워커가 아니라 드라이버 혼자 부담하는 작업이라, executor 메모리를 늘려도 드라이버가 먼저
OOM으로 죽었다.

시점 목록을 데이터로 다뤄 crossJoin 한 번으로 처리하면 실행계획 조각이 시점 수와 무관하게
1개로 고정된다. `rcept_no <= rebalance_date`나 `bas_dt <= rebalance_date` 같은 룩어헤드
방지 필터는 crossJoin 이후에도 시점 컬럼 기준으로 개별 적용되므로, 시점을 한 덩어리로
처리한다고 해서 다른 시점의 미래 데이터가 섞여 들어오지는 않는다. `Window.partitionBy`에
`rebalance_date`를 반드시 포함하는 것이 이 안전성의 핵심이다 — 이걸 빠뜨리면 서로 다른
시점의 종목이 한 랭킹/한 그룹에 섞이는 조용한 버그가 된다.

`screen_portfolio`는 여기에 더해 `strategies` 테이블(전략 수만큼의 작은 테이블, 21,870개
기준 약 1.9MB)을 `F.broadcast`로 조인해 셔플 없이 전략 축을 곱한다. 전략 수가 1,000개든
21,870개든 이 구조 자체는 바뀌지 않는다 — 전략 수·시점 수가 늘어도 "계획 조각 1개" 원칙이
유지되는 것이 이 설계의 핵심이다.

## 5. ValuePick(Spring Boot)과의 관계

valuepick-batch는 기존 ValuePick 서비스와 완전히 분리된 리포다. ValuePick 본 서비스는
[별도 리포](https://github.com/project-valuepick/valuepick)에서 관리되며, 로컬에 내려받은
`프로젝트/valuepick/`(이 리포에는 커밋하지 않음)의
Java 코드는 API 호출 로직(엔드포인트·인증·파싱)과 스크리닝/점수 로직을 참고하는 용도로만
읽으며, Entity/DTO/스케줄러/프로덕션 MySQL(`investdb`)은 절대 수정하지 않는다.

04번이 문턱값 그리드에서 점수 방식으로 전환된 배경: 애초의 PER/PBR/배당수익률 문턱값 그리드는
"이 그리드 안에서의 최선의 조합"을 찾을 뿐, ValuePick이 실제로 운영하는 추천 로직
(`Top100Service.scoreAll()`)과는 무관한 별개의 실험이었다. ValuePick이 실제로 잘 작동하는지
검증하려면 04번이 그 점수 로직 자체를 재현해서 백테스트해야 한다는 문제의식으로 전환이
결정됐다(`docs/PLAN_가치주점수_전환_20260731.md`). 전환 후에도 "전략 조합을 곱하면 연산량이
폭증한다"는 이 리포의 학습 목표를 유지하기 위해, 가중치 프리셋 자체를 7개 팩터 각각의 후보값
곱 조합(3^7=2,187개)으로 구성해 그리드 규모(21,870개)를 키웠다.

## 6. 05번이 MySQL을 쓰는 이유 — CLAUDE.md 8항의 유일한 예외

CLAUDE.md/PROGRESS.md의 원칙은 "MySQL을 Spark 잡 간 중간 데이터 전달 용도로 쓰지 않는다"이다.
05번은 이 원칙을 어기는 것이 아니라 예외로 규정된 지점이다: 01~04번 사이의 데이터 전달은
전부 Parquet이고, 05번은 그 최종 산출물(04번 출력)을 다른 Spark 잡이 다시 읽게 하려는 것이
아니라 **서빙**하려는 것이다. `strategy_performance`/`backtest_results` 테이블은 기존
ValuePick(Spring Boot) 프론트엔드/API가 조회할 대상이며, 파이프라인 내부에서 순환하는
중간 데이터가 아니다.

테이블 자체를 market별로 분리한다(`strategy_performance_all`/`_kospi`,
`backtest_results_all`/`_kospi`) — `--market` 인자에 따라 대상 테이블명이 정해지고,
적재 방식은 매 실행마다 그 테이블을 truncate하고 전체 재삽입한다(`market` 컬럼도 모든
행에 채워 넣어 어느 실행 결과인지 남긴다). run_id별 이력 관리는 현재 요구사항 밖이라
과설계로 판단해 제외했다 — "각 market의 최신 백테스트 결과 1벌"만 서빙하면 되는
요구사항이기 때문이다.
