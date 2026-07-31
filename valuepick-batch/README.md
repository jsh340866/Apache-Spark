# valuepick-batch

Spark 기반 가치투자 백테스팅 파이프라인. 이 리포의 목적은 **Apache Spark 학습**이며, 이
백테스트 파이프라인은 그 학습 대상이다.

**절대 원칙**: 기존 `ValuePick`(Spring Boot, MySQL)의 프로덕션 코드·스케줄러·DB를 건드리지
않는 완전히 분리된 리포. `프로젝트/valuepick/`의 Java 코드는 API 호출·스크리닝 로직을
참고하는 용도로만 읽는다.

## 프로젝트 목적

기존 ValuePick 서비스(`Top100Service.scoreAll()`)는 PER/PBR/ROE/ROA/부채비율/EPS성장률/
모멘텀 7개 팩터를 종목집합 내 백분위로 정규화해 가중합산한 점수로 종목을 추천한다. 이
백테스트는 그 로직을 그대로 재현해, "이 추천 로직대로 과거 특정 시점마다 계속 사고팔았다면
실제로 이득이었는가"를 실제 시세·재무 데이터로 검증한다.

가중치 조합(7개 팩터 × 후보값)과 포트폴리오 크기·리밸런싱 주기를 곱해 **21,870개 전략**을
동시에 백테스트한다 — "전략 조합을 곱하면 연산량이 폭증한다"는 것을 Spark로 실측하는 것이
이 프로젝트의 핵심 학습 목표다. 기존에 시도했던 PER/PBR/배당수익률 문턱값(threshold) 그리드
버전(1,000개 전략)도 `jobs/04_backtest_grid_threshold.py`로 보존돼 있다.

## 아키텍처

```
                    ┌─────────────┐
                    │ spark-master │  스케줄링 + spark-submit 드라이버 실행
                    └──────┬──────┘
              ┌────────────┴────────────┐
      ┌───────┴───────┐         ┌───────┴───────┐
      │ spark-worker-1 │         │ spark-worker-2 │   각 2코어 (메모리는 환경별 상이,
      │  (2 cores)     │         │  (2 cores)     │   docs/ENVIRONMENT.md 참고)
      └───────────────┘         └───────────────┘

  jupyter (검증·진단용, 8888/4041)   spark-history (실행 이력 조회, 18080)
  mysql (최종 결과 서빙용, 3307 — 기존 ValuePick MySQL 3306과 분리)
```

외부 API(DART/공공데이터포털/한국수출입은행) → Spark 잡이 Parquet으로 원천~중간~최종 데이터를
처리 → MySQL로 서빙. **MySQL을 잡 간 중간 데이터 전달에 쓰지 않는다** — 01~04번 사이는 항상
Parquet(`data/` 하위, `year=YYYY` 파티셔닝)으로만 주고받고, 05번이 최종 결과를 MySQL에
적재하는 것이 유일한 예외다(기존 ValuePick 프론트엔드/API가 조회할 수 있도록 서빙하는 용도).

상세 구조(각 잡의 함수 구성, 04번의 벡터화 설계, 클러스터 구성 이유)는
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 참고.

## 파이프라인 (jobs/)

```
01_ingest_raw  →  02_clean_prices  →  03_build_indicators  →  04_backtest_grid  →  05_export_to_mysql
   원천 수집         시세 정제           지표 계산               점수 방식 백테스트      MySQL 적재
```

각 화살표는 Parquet 파일로 이어진다 — 이전 잡의 출력 디렉토리가 다음 잡의 입력 디렉토리다.

### `01_ingest_raw.py` — 원천 수집

KRX 상장종목, DART 재무제표·배당·기업개황(업종코드), 공공데이터포털 주가를 API에서 직접
받아 Parquet으로 저장한다.

- `companies`: KRX 상장종목 + DART `corp_code` 매핑 + `induty_code`(업종코드, F-Score
  금융업 예외 판정용) — `bas_dt` 기준 전량 overwrite
- `prices`: 일별 시세. `snapshot_type`으로 `current`(백테스트 대상 구간)와
  `1m_ago`/`12m_ago`(모멘텀 계산용 스냅샷)를 구분
- `financials`, `dividends`: DART 종목별 재무제표·배당 (연도 단위, 재수집 방지 로직 있음)

출력: `data/raw/{companies,prices,financials,dividends}`

### `02_clean_prices.py` — 시세 정제

`prices`의 `current` 구간만 정제 대상으로 삼는다.

- 결측 거래일 보간: 종목별 거래일 캘린더 대비 빈 날짜를 직전 종가로 forward-fill
  (`is_interpolated` 플래그)
- 액면분할/병합 의심 탐지: 전일 대비 등락률이 임계치(-40%/+67%)를 벗어나면
  `split_suspected`로 표시만 함(자동 보정은 하지 않음 — 확정 판정에 필요한 분할 이벤트
  필드가 원천 API에 없기 때문)

출력: `data/cleaned/prices`

### `03_build_indicators.py` — 지표 계산

재무제표를 언피벗해 EPS/BPS/PER/PBR/ROE/부채비율/배당수익률/ROA/모멘텀/F-Score/EPS성장률을
계산한다. `notebooks/verify_indicators.ipynb`로 ValuePick 실제 계산값과 대조 검증했다
(79종목 중 78종목 8개 지표 완전 일치).

핵심 출력 컬럼:
- `eps`, `bps`, `net_income_krw`, `equity_krw`, `dividend_amount`: 04번이 리밸런싱 시점별로
  PER/PBR/배당수익률을 직접 재계산할 때 쓰는 원본 값(룩어헤드 바이어스 방지 핵심)
- `rcept_no`: DART 접수번호(앞 8자리가 실제 공시일). 04번이 `rcept_no <= 리밸런싱 시점`으로
  미래 정보 사용을 차단하는 핵심 필드
- `roe`, `roa`, `debt_ratio`, `momentum`, `f_score`, `eps_growth_rate`: 가격에 의존하지
  않는 원본값이라 04번이 재계산 없이 그대로 사용

출력: `data/indicators`

### `04_backtest_grid.py` — 전략 그리드 백테스트 (프로젝트 핵심)

`conf/strategies.yaml`의 전략(리밸런싱주기 × portfolio_size × 가중치프리셋) 각각에 대해,
매 리밸런싱 시점마다 F-Score 필터를 통과한 종목 중 7팩터 점수 상위 N종목으로 포트폴리오를
구성하고 다음 시점까지 보유했을 때의 수익률을 계산한다.

- **종목 선정**: ① `induty_code` 없는 종목 제외 ② 금융업(업종코드 앞 2자리 64/65/66)은
  F-Score 필터 면제, 그 외는 F-Score≥6만 통과 ③ PER/PBR/부채비율(낮을수록 고점수)과
  ROE/ROA/EPS성장률/모멘텀(높을수록 고점수) 7개 팩터를 백분위로 정규화해 전략별 가중치로
  가중합산 ④ 점수 상위 `portfolio_size`종목을 동일 비중 매수
- **가중치 프리셋**: ValuePick 원본 가중치(PER25%/PBR15%/ROE20%/ROA10%/부채비율15%/
  EPS성장률5%/모멘텀10%)를 기준으로 팩터마다 후보값 3개(원본 ±10%p, EPS성장률만 ±2%p)를
  곱 조합해 2,187개 프리셋을 만들고, 각 프리셋을 합 100%로 정규화한다
- **그리드 규모**: 리밸런싱(2) × portfolio_size(3/10/30/50/100, 5개) × 가중치프리셋(2,187개)
  = **21,870개 전략**
- **룩어헤드 바이어스 방지(이중 장치)**: 재무제표는 `rcept_no <= rebalance_date`로 그
  시점에 아직 공시되지 않은 사업보고서를 차단하고, 가격은 03번의 "최신가 고정" 값이 아니라
  그 리밸런싱 시점의 실제 종가로 PER/PBR/배당수익률을 매번 다시 계산한다
- **벡터화**: 시점·전략을 파이썬 for문으로 순회하지 않고 `crossJoin`+`Window.partitionBy`로
  한 번에 처리해, 전략 수가 늘어도 실행계획 조각이 1개로 고정되게 설계했다(상세:
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 4절)

출력: `data/backtest_results/{summary, period_returns}`

### `05_export_to_mysql.py` — MySQL 서빙

04번 출력을 Spark JDBC writer로 MySQL에 적재한다. `summary` → `strategy_performance`
(전략별 1행), `period_returns` → `backtest_results`(전략×구간별 1행). 매 실행마다 테이블을
truncate하고 전체 재삽입하며, `--market` 값을 모든 행에 `market` 컬럼으로 채워 넣는다.

## 데이터 흐름 요약

```
companies ─┐
prices ────┼─→ [02] cleaned/prices ─┐
financials ┤                        ├─→ [03] indicators ─┐
dividends ─┘                        │                    ├─→ [04] backtest_results ─→ [05] MySQL
                          strategies.yaml ────────────────┘
```

## 실행 방법

### 1. 환경변수 설정

```bash
cp docker/.env.example docker/.env
# docker/.env에 MYSQL_ROOT_PASSWORD 채우기
```

필요한 키:

| 환경변수 | 사용처 |
|---|---|
| `DART_API_KEY` | `01_ingest_raw.py` — 재무제표·배당·업종코드 수집 |
| `STOCK_API_KEY` | `01_ingest_raw.py` — KRX 상장종목·주가 수집 |
| `EXIM_API_KEY` | `03_build_indicators.py` — 외화 표시 재무제표 KRW 환산 |
| `MYSQL_ROOT_PASSWORD` | `05_export_to_mysql.py` — MySQL 접속 |

### 2. Spark 클러스터 기동

```bash
cd docker
docker compose up -d
```

- Spark 마스터 UI: http://localhost:8088 (8080은 기존 ValuePick 백엔드와 충돌 방지를 위해 분리)
- 드라이버/애플리케이션 UI: http://localhost:4040 (잡 실행 중일 때만 뜸)
- History Server(실행 이력 사후 조회): http://localhost:18080
- Jupyter: http://localhost:8888
- MySQL(서빙용, 기존 ValuePick과 별도): localhost:3307

### 3. 잡 실행

`--properties-file`을 빠뜨리면 클러스터 모드가 아닌 드라이버 로컬 모드로 실행되어 워커
분산이 전혀 안 되므로 항상 포함할 것.

```bash
# 01 — 원천 수집 (연도별로 실행)
docker exec spark-master spark-submit \
  --properties-file /opt/spark-apps/conf/spark-defaults.conf \
  /opt/spark-apps/jobs/01_ingest_raw.py \
  --year 2023 --bas-dt 20231229

# 02 — 시세 정제 (전체 연도 일괄)
docker exec spark-master spark-submit \
  --properties-file /opt/spark-apps/conf/spark-defaults.conf \
  /opt/spark-apps/jobs/02_clean_prices.py

# 03 — 지표 계산 (연도별로 실행)
docker exec spark-master spark-submit \
  --properties-file /opt/spark-apps/conf/spark-defaults.conf \
  --conf spark.sql.sources.partitionOverwriteMode=dynamic \
  /opt/spark-apps/jobs/03_build_indicators.py --year 2023

# 04 — 백테스트 그리드 (21,870개 전략 — 셔플 파티션 수를 그리드 규모에 맞출 것, 6절 참고)
docker exec spark-master spark-submit \
  --properties-file /opt/spark-apps/conf/spark-defaults.conf \
  --driver-memory 3g \
  --conf spark.sql.shuffle.partitions=64 \
  /opt/spark-apps/jobs/04_backtest_grid.py \
  --years 2021,2022,2023 --market ALL

# 05 — MySQL 적재
docker exec spark-master spark-submit \
  --properties-file /opt/spark-apps/conf/spark-defaults.conf \
  /opt/spark-apps/jobs/05_export_to_mysql.py \
  --input-dir /opt/spark-apps/data/backtest_results --market ALL
```

실행 시 반드시 지켜야 할 옵션:

- **03번의 `--conf spark.sql.sources.partitionOverwriteMode=dynamic`**: 기본 static
  모드는 연도별 재실행 시 이전 연도 파티션을 통째로 지운다.
- **04번의 `--driver-memory 3g`**: `--conf spark.driver.memory`는 클라이언트 모드에서
  이미 시작된 SparkContext에 적용되지 않는다. `spark-submit` CLI 플래그로만 반영된다.
- **04번의 `--conf spark.sql.shuffle.partitions`**: `spark-defaults.conf` 기본값(4)은
  1,000개 규모 그리드 기준이다. 21,870개 그리드를 기본값 그대로 돌리면 셔플 파티션 하나에
  데이터가 몰려 스필이 폭증해 실패한다 — 그리드 규모에 맞춰 override할 것(상세:
  [docs/PERFORMANCE.md](docs/PERFORMANCE.md) 2~4절).
- Windows Git Bash에서 `docker exec`에 절대경로 인자를 넘길 때는 앞에
  `MSYS_NO_PATHCONV=1`을 붙여야 경로 자동변환 오류를 피할 수 있다.

각 잡의 소요시간은 마스터 UI(http://localhost:8088)의 `Duration` 컬럼에 자동 기록되므로
`time` 명령으로 따로 잴 필요가 없다.

### 4. 결과 시각화 — Jupyter 노트북

단계별 산출물 확인 및 백테스트 결과 시각화용 노트북이 `notebooks/`에 있다.

| 노트북 | 확인 대상 |
|---|---|
| `check_01_raw.ipynb` | companies/prices/financials/dividends 원본 |
| `check_02_cleaned_prices.ipynb` | 보간·분할의심 플래그·결측치 |
| `check_03_indicators.ipynb` | 지표 계산 결과 |
| `verify_indicators.ipynb` | 03 결과와 Java 계산값 자동 대조 |
| `check_04_backtest.ipynb` | **점수 방식(21,870개 전략)** 성과 랭킹, monthly/quarterly 비교, 가중치프리셋별 성과·상관관계 분석, 결과 해석 |
| `check_04_backtest_threshold.ipynb` | 기존 문턱값 그리드(1,000개 전략) 버전 — 조건별 히트맵/시계열, 결과 해석 |
| `check_04_02_backtest.ipynb` | 이상치 제외 + 보유종목수 상한없음 진단 |

`check_04_backtest.ipynb`는 가중치프리셋(7팩터 가중치)별 누적수익률·샤프비율을 비교하고,
어떤 팩터 가중치가 이 표본에서 성과와 상관관계가 높았는지(예: PBR 가중치 상관계수 0.505,
모멘텀 가중치 -0.362) 그래프로 확인할 수 있다. **이 결과는 2021~2023년 표본 안에서의 사후
관찰이지 일반화된 "최적 가중치"가 아니다** — 노트북 마지막 "결과 해석" 섹션에 상세 근거와
함께 정리돼 있다.

노트북 작업 시 주의:

- 커널을 탭만 닫고 종료하지 않으면 클러스터 코어를 계속 점유해 다른 잡이 대기 상태에 빠진다.
  끝나면 `spark.stop()` 또는 Kernel Shut Down.
- `.ipynb`를 코드로 직접 수정한 뒤에는 Jupyter 탭을 반드시 닫았다가 새로 열 것.
- Jupyter 컨테이너(Python 3.11)와 워커 컨테이너(Python 3.8)는 파이썬 마이너 버전이 달라,
  노트북에서 `spark.createDataFrame()`으로 파이썬 객체를 직접 워커에 보내면
  `PYTHON_VERSION_MISMATCH` 에러가 난다. `spark.read.parquet` 등 워커가 파일을 직접 읽는
  경로는 문제없다 — `check_04_backtest.ipynb`는 이 문제를 pandas merge로 우회한다.

## 문서

| 문서 | 내용 |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 파이프라인 구조, 클러스터 구성, 04번 벡터화 설계, ValuePick과의 관계 |
| [docs/VALIDATION.md](docs/VALIDATION.md) | 검증 이력 — 지표 대조검증, 분산 처리 비결정성 버그 3건, 벡터화 대조검증, 이상치 처리, 점수 전환 검증 |
| [docs/PERFORMANCE.md](docs/PERFORMANCE.md) | 성능 실측 — 계획 트리 폭발/벡터화 효과, 21,870개 실행 시 셔플 파티션 튜닝, 워커 스케일링 벤치마크 중단 경위 |
| [docs/PLAN_가치주점수_전환_20260731.md](docs/PLAN_가치주점수_전환_20260731.md) | 04번을 문턱값 그리드에서 점수 방식으로 전환한 계획 원본 |
| [docs/ENVIRONMENT.md](docs/ENVIRONMENT.md) | 실행 환경(학원/집 컴퓨터)별 리소스 설정 차이 |
| [PROGRESS.md](PROGRESS.md) | 세션별 작업 이력, 알려진 이슈, 다음 할 일 |

## 데이터 저장 원칙

- 원천~중간 데이터: Parquet (`data/raw`, `data/cleaned`, `data/indicators`,
  `data/backtest_results`), `year=YYYY` 파티셔닝
- 최종 결과: MySQL (`strategy_performance`, `backtest_results` 테이블)
- MySQL을 Spark 잡 간 중간 데이터 전달 용도로 사용하지 않는다 — 05번만의 예외.
