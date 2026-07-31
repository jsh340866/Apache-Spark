# valuepick-batch

Spark 기반 가치투자 백테스팅 파이프라인. 상세 설계는 [PROJECT_INSTRUCTIONS.md](../spark프로젝트/PROJECT_INSTRUCTIONS.md) 참고.

**절대 원칙**: 기존 `ValuePick`(Spring Boot, MySQL)의 프로덕션 코드·스케줄러·DB를 건드리지 않는 완전히 분리된 리포.

## 프로젝트 방향

데이터를 수집해 기준값(PER 상한/PBR 상한/배당수익률 하한 등)을 정하고, 그 기준에 맞는 종목을 그때그때 샀다면 실제로 이득이었는지를 과거 데이터로 검증한다. 기준을 1,000가지 조합(`conf/strategies.yaml`)으로 만들어두고, 각 조합에 대해 "과거 특정 시점으로 돌아가 그 기준대로 계속 사고팔았다면 최종적으로 얼마나 벌었을까"를 시뮬레이션한다.

## 아키텍처

```
                    ┌─────────────┐
                    │ spark-master │  스케줄링만, 계산 안 함
                    └──────┬──────┘
              ┌────────────┴────────────┐
      ┌───────┴───────┐         ┌───────┴───────┐
      │ spark-worker-1 │         │ spark-worker-2 │   각 2코어 / 2GB
      │  (2 cores)     │         │  (2 cores)     │   총 4코어, shuffle.partitions=4
      └───────────────┘         └───────────────┘

  jupyter (진단·검증용, 4041)        mysql (서빙용, 3307 — 05번 미구현이라 아직 비어있음)
```

외부 API(DART/공공데이터포털/한국수출입은행) → Spark 잡이 Parquet으로 원천~중간~최종 데이터를 전부 처리 → (미구현) MySQL로 서빙. **MySQL을 잡 간 중간 데이터 전달에 쓰지 않는다** — 잡과 잡 사이는 항상 Parquet(`data/` 하위, `year=YYYY` 파티셔닝)로만 주고받는다.

## 파이프라인 (jobs/)

```
01_ingest_raw  →  02_clean_prices  →  03_build_indicators  →  04_backtest_grid  →  (05_export_to_mysql, 미구현)
   원천 수집         시세 정제           지표 계산               백테스트 그리드         MySQL 적재
```

각 화살표는 Parquet 파일로 이어진다 — 이전 잡의 출력 디렉토리가 다음 잡의 입력 디렉토리다.

### `01_ingest_raw.py` — 원천 수집

KRX 상장종목, DART 재무제표·배당, 공공데이터포털 주가를 API에서 직접 받아 Parquet으로 저장한다. 기존 ValuePick(Spring Boot)의 MySQL은 거치지 않는다.

- `companies`: KRX 상장종목 + DART corpCode 매핑 (`bas_dt` 기준 전량 overwrite)
- `prices`: 일별 시세. `snapshot_type` 컬럼으로 두 종류를 구분해 저장
  - `current`: 지정한 날짜 범위 전체 (백테스트 대상 구간)
  - `1m_ago`/`12m_ago`: 모멘텀 계산용 스냅샷, 최근 수집일 기준 1개월 전/12개월 전 1건씩만 (이미 있으면 재수집 스킵)
- `financials`, `dividends`: DART 종목별 재무제표·배당 (연도 단위, 재수집 방지 로직 있음 — DART 일일 호출 제한 대응)

출력: `data/raw/{companies,prices,financials,dividends}`

### `02_clean_prices.py` — 시세 정제

`prices`의 `current` 구간만 정제 대상으로 삼는다(`1m_ago`/`12m_ago`는 시점이 뚝 떨어져 있어 같이 섞으면 그 사이가 전부 결측으로 오인되므로 원본 그대로 통과).

- 결측 거래일 보간: 종목별 거래일 캘린더 대비 빈 날짜를 직전 종가로 forward-fill (`is_interpolated` 플래그)
- 액면분할/병합 의심 탐지: 전일 대비 등락률이 임계치(-40%/+67%)를 벗어나면 `split_suspected`로 표시만 함 (자동 보정은 하지 않음 — 원천 API에 분할 이벤트 필드가 없어 확정 판정이 불가능하기 때문)

출력: `data/cleaned/prices`

### `03_build_indicators.py` — 지표 계산

재무제표를 언피벗해 EPS/BPS/PER/PBR/ROE/부채비율/배당수익률/ROA/모멘텀/F-Score/EPS성장률을 계산한다. 기존 Spring Boot `FinancialIndicatorService`의 계산 로직을 재현하는 게 목표이며 `notebooks/verify_indicators.ipynb`로 대조 검증한다.

핵심 출력 컬럼:
- `eps`, `bps`, `net_income_krw`, `equity_krw`, `dividend_amount`: 04번이 리밸런싱 시점별로 PER/PBR/배당수익률을 직접 재계산할 때 쓰는 원본 값 (룩어헤드 바이어스 방지 핵심)
- `per`, `pbr`, `dividend_yield`: 최신 종가 기준 계산값. 04번 스크리닝에는 쓰이지 않으며 `verify_indicators.ipynb`의 Java 대조 검증 전용으로 유지
- `rcept_no`: DART 접수번호(앞 8자리가 실제 공시일). 04번이 `rcept_no <= 리밸런싱 시점`으로 미래 정보 사용을 차단하는 핵심 필드

출력: `data/indicators`

### `04_backtest_grid.py` — 백테스트 그리드 (프로젝트 핵심)

`conf/strategies.yaml`의 전략 조합마다, 매 리밸런싱 시점(monthly 36개/quarterly 12개)에 조건을 만족하는 종목으로 포트폴리오를 구성하고 다음 시점까지 보유했을 때의 수익률을 시뮬레이션한다.

- **룩어헤드 바이어스 방지 (이중 장치)**
  - 재무제표: `rcept_no <= 리밸런싱 시점`으로, 그 시점에 아직 공시되지 않은 사업보고서는 쓰지 않는다
  - 가격: 03번이 계산해둔 "최신가 기준 고정 PER/PBR"을 쓰지 않고, `compute_point_in_time_ratios()`가 그 리밸런싱 시점의 실제 종가로 PER/PBR/배당수익률을 매번 다시 계산한다
- **종목 선정**: 조건(PER 상한/PBR 상한/배당수익률 하한)을 만족하는 종목 중 PER 낮은 순 상위 `portfolio_size`개, 동일 비중
- **`portfolio_size` 후보**: `10 / 30 / 50 / 100 / 9999(=사실상 무제한, 이름은 "ALL"로 표시)`. 종목 수가 최대치인 종목 총수를 넘지 않는 9999를 넣어 "조건만 만족하면 전부 매수"를 표현 — PER/PBR 조건 자체가 시장 평균 대비 선별 효과가 있는지 검증하는 대조군
- **성능**: 시점별 union이 lineage를 계속 이어붙여 드라이버 OOM을 일으켰던 이력이 있어, 각 단계마다 `.cache()` + `.count()`로 즉시 실체화해 lineage를 끊는다

출력: `data/backtest_results/{period_returns, summary}`

### `05_export_to_mysql.py` — 미구현

04번 결과(`backtest_results`, `strategy_performance`)를 Spark JDBC writer로 MySQL에 upsert할 예정.

## 데이터 흐름 요약

```
companies ─┐
prices ────┼─→ [02] cleaned/prices ─┐
financials ┤                        ├─→ [03] indicators ─┐
dividends ─┘                        │                    ├─→ [04] backtest_results
                          strategies.yaml ────────────────┘
```

## 현재 진행 상태 (TODO)

- [x] 1. `docker/docker-compose.yml` — Spark 클러스터(master + worker 2) + Jupyter + MySQL
- [x] 2. `jobs/01_ingest_raw.py` — KRX/DART/공공데이터 API → Parquet 저장 (MySQL 미경유)
- [x] 3. `jobs/02_clean_prices.py`, `jobs/03_build_indicators.py`
- [x] 4. `notebooks/verify_indicators.ipynb` — 03 결과와 Spring Boot 계산값 대조 (79종목 중 78종목 8개 지표 완전 일치)
- [x] 5. `conf/strategies.yaml` — 1,000개 전략 조합 (`conf/generate_strategies.py`로 생성)
- [x] 6. `jobs/04_backtest_grid.py` — 구현·실행 완료. **튜닝 전/후 실행시간 비교는 미실측**
- [ ] 7. `jobs/05_export_to_mysql.py`
- [ ] 8. `docs/PERFORMANCE.md`, `docs/ARCHITECTURE.md`, `docs/VALIDATION.md`

단계별 상세 진행 상황과 알려진 이슈는 [PROGRESS.md](PROGRESS.md) 참고.

## 실행 방법

### 1. 환경변수 설정

```bash
cp docker/.env.example docker/.env
# docker/.env에 MYSQL_ROOT_PASSWORD 채우기
```

필요한 키 (전부 기존 ValuePick과 동일한 발급 키 사용 가능):

| 환경변수 | 사용처 |
|---|---|
| `DART_API_KEY` | `01_ingest_raw.py` — 재무제표·배당 수집 |
| `STOCK_API_KEY` | `01_ingest_raw.py` — KRX 상장종목·주가 수집 |
| `EXIM_API_KEY` | `03_build_indicators.py` — 외화 표시 재무제표 KRW 환산 |

### 2. Spark 클러스터 기동

```bash
cd docker
docker compose up -d
```

- Spark 마스터 UI: http://localhost:8088 (8080은 기존 ValuePick 백엔드와 충돌 방지를 위해 분리)
- Jupyter: http://localhost:8888
- MySQL(서빙용, 기존 ValuePick과 별도): localhost:3307

### 3. 잡 실행

`--properties-file`을 빠뜨리면 클러스터 모드가 아닌 드라이버 로컬 모드로 실행되어 워커 분산이 전혀 안 되므로 항상 포함할 것.

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

# 04 — 백테스트 그리드
docker exec spark-master spark-submit \
  --properties-file /opt/spark-apps/conf/spark-defaults.conf \
  --driver-memory 3g \
  /opt/spark-apps/jobs/04_backtest_grid.py \
  --years 2021,2022,2023 --market KOSPI
```

실행 시 반드시 지켜야 할 옵션:

- **03번의 `--conf spark.sql.sources.partitionOverwriteMode=dynamic`**: `mode("overwrite")+partitionBy("year")`는 기본이 static 모드라 연도별로 나눠 실행하면 이전 연도 파티션이 통째로 지워진다.
- **04번의 `--driver-memory 3g`**: `--conf spark.driver.memory`는 클라이언트 모드에서 이미 시작된 SparkContext에 적용되지 않는다. `spark-submit` CLI 플래그로만 실제 반영된다.
- Windows Git Bash에서 `docker exec`에 절대경로 인자를 넘길 때는 앞에 `MSYS_NO_PATHCONV=1`을 붙여야 경로 자동변환 오류를 피할 수 있다.

각 잡의 소요시간은 마스터 UI(http://localhost:8088)의 `Duration` 컬럼에 자동 기록되므로 `time` 명령으로 따로 잴 필요가 없다.

### 4. 결과 확인

단계별 산출물 확인용 Jupyter 노트북이 `notebooks/`에 있다.

| 노트북 | 확인 대상 |
|---|---|
| `check_01_raw.ipynb` | companies/prices/financials/dividends 원본 |
| `check_02_cleaned_prices.ipynb` | 보간·분할의심 플래그·결측치 |
| `check_03_indicators.ipynb` | 지표 계산 결과 |
| `verify_indicators.ipynb` | 03 결과와 Java 계산값 자동 대조 |
| `check_04_backtest.ipynb` | 04 성과 랭킹, monthly/quarterly 비교, 시계열 그래프 |
| `check_04_01_backtest.ipynb` | KOSPI 워커 2대 vs 4대 결과 비교 |
| `check_04_02_backtest.ipynb` | 이상치 제외 + 보유종목수 상한없음 진단 (**실행 검증 미완**) |

노트북 작업 시 주의:

- 커널을 탭만 닫고 종료하지 않으면 클러스터 코어를 계속 점유해 다른 잡이 대기 상태에 빠진다. 끝나면 `spark.stop()` 또는 Kernel Shut Down.
- `.ipynb`를 코드로 직접 수정한 뒤에는 Jupyter 탭을 반드시 닫았다가 새로 열 것. 브라우저 탭이 이전 버전을 물고 있으면 저장 시 디스크의 최신 수정사항이 통째로 덮어써진다.

## 데이터 저장 원칙

- 원천~중간 데이터: Parquet (`data/raw`, `data/cleaned`, `data/indicators`, `data/backtest_results`), `year=YYYY` 파티셔닝
- 최종 결과: MySQL (`backtest_results`, `strategy_performance`) — 05번 미구현
- MySQL을 Spark 잡 간 중간 데이터 전달 용도로 사용하지 않는다.
