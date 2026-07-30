# valuepick-batch

Spark 기반 가치투자 백테스팅 파이프라인. 상세 설계는 [PROJECT_INSTRUCTIONS.md](../.claude/spark프로젝트/PROJECT_INSTRUCTIONS.md) 참고.

**절대 원칙**: 기존 `ValuePick`(Spring Boot, MySQL)의 프로덕션 코드·스케줄러·DB를 건드리지 않는 완전히 분리된 리포.

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
