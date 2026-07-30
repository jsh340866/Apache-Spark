# valuepick-batch

Spark 기반 가치투자 백테스팅 파이프라인. 상세 설계는 [PROJECT_INSTRUCTIONS.md](../프로젝트/.claude/spark프로젝트/PROJECT_INSTRUCTIONS.md) 참고.

**절대 원칙**: 기존 `ValuePick`(Spring Boot, MySQL)의 프로덕션 코드·스케줄러·DB를 건드리지 않는 완전히 분리된 리포.

## 현재 진행 상태 (TODO)

- [x] 1. `docker/docker-compose.yml` — Spark 클러스터(master + worker 2) + Jupyter + MySQL
- [x] 2. `jobs/01_ingest_raw.py` — KRX/DART/공공데이터 API → Parquet 저장 (MySQL 미경유)
- [ ] 3. `jobs/02_clean_prices.py`, `jobs/03_build_indicators.py`
- [ ] 4. `notebooks/verify_indicators.ipynb` — 03 결과와 Spring Boot 계산값 대조
- [ ] 5. `conf/strategies.yaml` — 전략 파라미터 그리드 정의 (최소 500 조합)
- [ ] 6. `jobs/04_backtest_grid.py` — 튜닝 전/후 실행시간 비교 포함
- [ ] 7. `jobs/05_export_to_mysql.py`
- [ ] 8. `docs/PERFORMANCE.md`, `docs/ARCHITECTURE.md`, `docs/VALIDATION.md`

## 실행 방법

### 1. 환경변수 설정

```bash
cp docker/.env.example docker/.env
# docker/.env에 MYSQL_ROOT_PASSWORD 채우기
```

`jobs/01_ingest_raw.py`는 `DART_API_KEY`, `STOCK_API_KEY` 환경변수가 필요하다 (기존 ValuePick과 동일한 발급 키 사용 가능).

### 2. Spark 클러스터 기동

```bash
cd docker
docker compose up -d
```

- Spark 마스터 UI: http://localhost:8088 (8080은 기존 ValuePick 백엔드와 충돌 방지를 위해 분리)
- Jupyter: http://localhost:8888
- MySQL(서빙용, 기존 ValuePick과 별도): localhost:3307

### 3. 01_ingest_raw.py 실행 (스크립트 예시)

```bash
docker exec spark-master spark-submit \
  --properties-file /opt/spark-apps/conf/spark-defaults.conf \
  /opt/spark-apps/jobs/01_ingest_raw.py \
  --year 2023 --bas-dt 20231229
```

## 데이터 저장 원칙

- 원천~중간 데이터: Parquet (`data/raw`, `data/cleaned`, `data/indicators`), `year=YYYY` 파티셔닝
- 최종 결과: MySQL (`backtest_results`, `strategy_performance`)
- MySQL을 Spark 잡 간 중간 데이터 전달 용도로 사용하지 않는다.
