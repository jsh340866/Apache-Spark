# 작업 진행 체크리스트 (2026-07-30 기준)

새 세션에서 이어서 작업할 때 참고. 전체 설계 원본은 `../.claude/spark프로젝트/PROJECT_INSTRUCTIONS.md`.

## 완료

### 인프라
- [x] 리포 기본 구조 생성 (`docker/`, `jobs/`, `conf/`, `notebooks/`, `docs/`, `data/`)
- [x] `.gitignore` — `data/`, `.env`, Python/Spark 캐시 제외
- [x] `requirements.txt` — pyspark, requests, PyYAML
- [x] `docker/docker-compose.yml` — spark-master + worker×2(각 2코어) + jupyter + mysql(3307, 기존 ValuePick MySQL 3306과 충돌 방지)
- [x] `docker/.env.example` — MySQL 비밀번호 등 민감정보 분리
- [x] `conf/spark-defaults.conf` — shuffle partition(4, 워커 총 4코어 기준), broadcast join 임계값(10MB)
- [x] `docker compose config`로 문법 검증 완료
- [x] `README.md` — 진행 상태 + 실행 방법

### jobs/01_ingest_raw.py
- [x] KRX 상장종목 수집 (`fetch_krx_listed`) — JSON, KOSPI/KOSDAQ 필터, 스팩·리츠 제외
- [x] DART corpCode 매핑 (`fetch_dart_corp_code_map`) — ZIP 안 XML 파싱 (DART 자체 스펙상 유일한 XML 응답)
- [x] 주가 수집 (`fetch_stock_prices`) — JSON, 기준일 1회 호출로 전 종목 수신
- [x] DART 재무제표 수집 (`fetch_financial_statement`) — CFS 우선, 없으면 OFS 재시도 (재시도 3회)
- [x] DART 배당 수집 (`fetch_dividend`) — 재시도 1회
- [x] 재수집 방지 로직 (`already_ingested`) — DART 일일 콜 제한(10,000콜) 대응
- [x] Parquet 저장 (companies/prices/financials/dividends, year 파티셔닝)
- [x] API 키는 환경변수(`DART_API_KEY`, `STOCK_API_KEY`)로만 주입, 하드코딩 없음

기존 ValuePick(Spring Boot) 재사용 대상 파일 — 엔드포인트/인증/파싱만 참고, MySQL 저장 로직은 미사용:
- `valuepick/.../service/StockPriceCollector.java`
- `valuepick/.../service/DartCompanyCollector.java`
- `valuepick/.../service/DartFinancialCollector.java`
- `valuepick/.../service/DividendCollector.java`

### 진행 중 정정한 사항
- CLAUDE.md에는 "공공데이터포털 주가 API = XML 파싱"이라 적혀 있었으나, 실제 `StockPriceCollector.java`를 읽어보니 `resultType=json`으로 JSON을 바로 받고 있음. XML 파싱이 실제로 필요한 곳은 DART `corpCode.xml`(ZIP 압축) 하나뿐. 이 기준으로 코드 작성함.

### jobs/02_clean_prices.py
- [x] 결측 거래일 보간 (forward-fill) + `is_interpolated` 플래그
- [x] 액면분할/병합 의심 탐지 (-40%/+67% 임계치) + `split_suspected` 플래그만 표시, 자동 보정/제거는 하지 않음
- [x] snapshot_type="current"만 정제 대상, 1m_ago/12m_ago는 원본 그대로 통과 후 재합류

### jobs/03_build_indicators.py
- [x] DART 재무제표 3개년(thstrm/frmtrm/bfefrmtrm) stack()으로 언피벗
- [x] account_id 우선/account_nm 차선 매칭 + sj_div(BS/CIS/IS/CF) 필터링으로 계정 오매칭 방지
- [x] CFS 우선, OFS 폴백
- [x] 한국수출입은행 환율 조회 및 외화 재무제표 KRW 환산
- [x] EPS/BPS/PER/PBR/ROE/부채비율/배당수익률/ROA/모멘텀/F-Score/EPS성장률 계산
- [x] `rcept_no`(DART 접수번호, 앞 8자리가 실제 공시일 YYYYMMDD) 컬럼 추가 — 04번 룩어헤드 바이어스 방지 조인용. 같은 year가 여러 rcept_no에서 유래할 경우 가장 이른(보수적) 값 채택

### notebooks/verify_indicators.ipynb
- [x] ValuePick 로컬 DB 역주입 후 `/admin/indicator/calculate` API로 Java 실제 계산값과 대조
- [x] 79종목 중 78종목 8개 지표(EPS/BPS/PER/PBR/ROE/부채비율/배당수익률/ROA) 완전 일치, 1종목 PER 반올림 오차(0.08) 확인
- [x] `pivot_financials` 버그 발견/수정: `ifrs-full_Equity`가 BS/SCE 양쪽에 중복 등장해 `F.first()` 순서 비의존적으로 만들기 위해 sj_div 필터링 적용
- 상세 내용: `../.claude/spark프로젝트/작업요약_Spark배치_01-03구현및검증_20260729.md` 참고

### conf/strategies.yaml
- [x] `conf/generate_strategies.py` — PER 5 x PBR 5 x 배당수익률 4 x 리밸런싱주기 2 x 보유종목수 5 = 1,000개 조합 자동 생성
- [x] 파라미터 범위: PER(8/10/12/15/20), PBR(0.8/1.0/1.2/1.5/2.0), 배당수익률하한(null/1.0/2.0/3.0), 주기(monthly/quarterly), 보유종목수(10/20/30/50/100)

### 데이터 수집 현황 (2026-07-30)
- companies/prices: 2,555종목(KOSPI+KOSDAQ) 전체 확보 (bas_dt=20260724 기준)
- financials/dividends: 기존 `--dart-limit` 제한(86/85종목)으로 받았던 것을 전체 재수집 중 — 기존 year=2023 파티션 삭제 후 제한 없이 재실행, 완료 대기

## 미완료 (지침서 섹션 6 순서대로)

- [ ] 6. `jobs/04_backtest_grid.py` — 프로젝트 핵심. 튜닝 없는 최초 버전 → 실행시간 측정 → 브로드캐스트조인/파티션수/캐싱 튜닝 적용 → 재측정. **튜닝 전 수치를 반드시 먼저 실측**(나중에 추정 금지). 룩어헤드 바이어스 방지(`rcept_no <= rebalance_date`) 필수 — indicators에 rcept_no 이미 추가됨
- [ ] 7. `jobs/05_export_to_mysql.py` — Spark JDBC writer로 `backtest_results`, `strategy_performance` 테이블 upsert
- [ ] 8. `docs/PERFORMANCE.md`, `docs/ARCHITECTURE.md`, `docs/VALIDATION.md` — 진행하면서 계속 갱신 (마지막에 몰아쓰지 않기)

## 하지 말아야 할 것 (지침서 7항)
- 기존 ValuePick의 Entity/DTO/스케줄러 직접 수정 금지
- MySQL을 Spark 잡 간 중간 데이터 전달 용도로 사용 금지 (전부 Parquet)
- 리밸런싱 시점 이후 공시된 재무 데이터 사용 금지 (룩어헤드 바이어스)
- 튜닝 먼저 적용하고 "전" 수치를 나중에 추정해서 기록 금지