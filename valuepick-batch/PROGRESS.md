# 작업 진행 체크리스트 (2026-07-30 기준)

새 세션에서 이어서 작업할 때 참고. 전체 설계 원본은 `../.claude/spark프로젝트/PROJECT_INSTRUCTIONS.md`.

## 완료

### 인프라
- [x] 리포 기본 구조 생성 (`docker/`, `jobs/`, `conf/`, `notebooks/`, `data/`) — `docs/`는 아직 미생성
- [x] `docker/Dockerfile.spark` — `apache/spark:3.5.0` 기반, requests/PyYAML/MySQL JDBC 드라이버를 이미지에 포함 (`bitnami/spark:3.5`가 Docker Hub 무료 배포에서 제거되어 교체)
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
- [x] **fan-out 버그 수정 (심각도 높음)** — 지표 결과가 종목당 1행이 아니라 최대 50행까지 중복돼 있었음(2021년 기준 55,182행 vs 실제 종목수 2,368).
  - 원인 1(핵심): `join_momentum`이 `stock_code`만으로 조인하는데, 01번을 여러 날 반복 실행하면서 `1m_ago`/`12m_ago` 스냅샷이 종목당 여러 `bas_dt`로 append됨 → `_latest_snapshot()`으로 종목별 최신 `bas_dt` 1건만 사용하도록 수정
  - 원인 2(부차): DART 배당 원본에 같은 키로 값 있는 행과 공백(`-`) 행이 동시 존재 → 원본 `thstrm`이 실제 값을 가진 행만 non-null로 남기고 `F.first(ignorenulls=True)` 적용
  - **교훈**: 79종목 초기 검증(2026-07-29) 당시엔 01번이 한 번만 실행된 상태라 드러나지 않았음. 배치를 여러 번 재실행하는 실제 운영 환경에서만 나타나는 버그였음
  - 재실행 후 종목당 정확히 1행(2,368건) 검증 완료

### notebooks/verify_indicators.ipynb
- [x] ValuePick 로컬 DB 역주입 후 `/admin/indicator/calculate` API로 Java 실제 계산값과 대조
- [x] 79종목 중 78종목 8개 지표(EPS/BPS/PER/PBR/ROE/부채비율/배당수익률/ROA) 완전 일치, 1종목 PER 반올림 오차(0.08) 확인
- [x] `pivot_financials` 버그 발견/수정: `ifrs-full_Equity`가 BS/SCE 양쪽에 중복 등장해 `F.first()` 순서 비의존적으로 만들기 위해 sj_div 필터링 적용
- 상세 내용: `../.claude/spark프로젝트/작업요약_Spark배치_01-03구현및검증_20260729.md` 참고

### conf/strategies.yaml
- [x] `conf/generate_strategies.py` — PER 5 x PBR 5 x 배당수익률 4 x 리밸런싱주기 2 x 보유종목수 5 = 1,000개 조합 자동 생성
- [x] 파라미터 범위: PER(8/10/12/15/20), PBR(0.8/1.0/1.2/1.5/2.0), 배당수익률하한(null/1.0/2.0/3.0), 주기(monthly/quarterly), 보유종목수(10/20/30/50/100)

### jobs/04_backtest_grid.py
- [x] `conf/strategies.yaml` 1,000개 전략 × 리밸런싱 시점별 스크리닝 → 구간 수익률 → 누적수익률/MDD/샤프비율 요약 파이프라인 구현
- [x] 룩어헤드 바이어스 방지 — `rcept_no <= rebalance_date` 조건. `latest_valid_indicators`가 시점마다 종목별 최신 유효 `rcept_no`를 자동 판단
- [x] monthly(2021-01~2023-12 월말 36시점)/quarterly(3·6·9·12월말 12시점) 실제 구분 구현. 재무제표는 연 1회뿐이므로 "재무 기준은 그 시점 이전 최신 사업보고서를 계속 사용, 가격은 매월/매분기 재평가" 방식
- [x] `--market KOSPI/KOSDAQ/ALL` 필터 옵션 (`companies.corp_cls` 기준)
- [x] 종목 선정: 조건(PER/PBR/배당수익률하한) 만족 종목 중 PER 낮은 순 상위 N종목, 동일비중
- [x] **드라이버 OOM 해결** — for루프로 `unionByName`을 반복 호출하는 구조가 실행계획(lineage)을 시점 수(48개)만큼 이어붙여 OOM 발생. `.cache()` + `.count()`로 각 단계마다 즉시 실체화해 lineage를 끊음
- [x] 배당수익률 원본이 없는 연도는 null → `dividend_yield_min`이 걸린 전략에서 조건 불충족으로 처리(null을 통과로 오판하지 않도록)
- [x] **`summarize_performance`의 `final_cum_return` 비결정성 수정 (2026-07-30)** — `groupBy().agg(F.last())`는 셔플 이후 행 순서에 의존하는 비결정적 함수라 마지막 구간 값이 나온다는 보장이 없었음(03번에서 `F.first()`로 이미 두 번 겪은 것과 같은 패턴). orderBy·프레임을 명시한 윈도우의 `F.last()`로 마지막 행 값을 채운 뒤 집계하도록 변경. **기존에 산출된 결과 수치는 재실행 후 재확인 필요**
- [x] **MDD 시작점 누락 수정 (2026-07-30)** — `running_peak`이 누적수익률의 최댓값이라 자산곡선 시작점(누적 0%)이 고점 후보에서 빠져 있었음. 첫 구간부터 하락한 전략은 그 낙폭이 MDD에 안 잡힘(첫 구간 -20% → peak=-0.2, drawdown=0). `F.greatest(..., 0.0)`으로 하한을 0에 걸어 수정

### 진단용 노트북
- [x] `notebooks/check_04_backtest.ipynb` — 성과 랭킹, monthly/quarterly 비교, 특정 전략 시계열, held_count 추이, null 결과 확인
- [x] `notebooks/check_04_01_backtest.ipynb` — KOSPI 워커 2대 vs 4대 결과 비교
- [ ] `notebooks/check_04_02_backtest.ipynb` — 이상치(`split_suspected`) 제외 + 보유종목수 상한없음(n=ALL) 진단. Python for루프 70회 `unionByName` 방식이 Catalyst 재분석 지연/드라이버 OOM을 일으켜 `crossJoin` + `Window.partitionBy(..., "rebalance_date")` 벡터화로 재작성했으나 **최종 실행 검증 미완**

### 백테스트 결과 해석 (버그 아님을 실측 검증)
- 초기 결과(전체 시장, FY2020 보강 전) 1,000개 전략 전부 음수(평균 -48%) → 버그 의심해서 전체 종목 실제 가격 기준선과 대조. 2021~2023 전체 종목 평균 +0.6%, **중앙값 -21.8%** (소수 극단적 급등주가 평균만 끌어올림). 저PER/저PBR이 고르는 소형·저유동성 종목군이 이 하락장에서 특히 부진(밸류 트랩 가능성)
- KOSPI 필터 + FY2020 보강 버전에서는 최고 성과 전략 **+72.6%** — 시장/데이터 범위에 따라 결과가 크게 달라짐
- **종목 101140 급등 이상치**: `per8_pbr1.2_dynone_monthly_n10`의 2023-09-30~10-31 누적수익률이 +179%로 튄 원인. 해당 종목이 467원 → 9,340원(+1,900%) 급등, 10종목 동일비중이라 `1900%/10 = +190%p`가 나머지 9종목 손실을 뒤엎음. 계산 자체는 정확(179.02% ≈ 실측 179.03%). 다만 매수/매도 시점의 `split_suspected`가 `False`로 찍혀 있어 분할/병합 탐지가 이 케이스를 놓쳤을 가능성 있음 — **정확한 발생 일자 미확인**

### 데이터 수집 현황 (2026-07-30)
- companies/prices: 2,555종목(KOSPI+KOSDAQ) 전체 확보
- financials/dividends: `--dart-limit` 없이 전체 2,555종목 대상, **2020~2023 4개년** 각 연도 자체의 실제 사업보고서를 개별 수집 완료(연도별 `--year` 재실행)
- 일별 시세: 2021(247거래일)/2022(245거래일)/2023(245거래일) 3개년 전체 확보
- DART API 키 일일 호출 한도를 개발자센터에서 40,000건으로 상향 후 진행

**FY2020 보강이 필요했던 이유**: 12월 결산 회사는 "2021년 사업연도" 보고서를 2022년 3월에야 공시하므로, 2021년 초중반 리밸런싱에는 FY2020 보고서가 있어야 스크리닝이 가능하다. FY2020 추가 수집 결과 2021-01/02월 표본이 25~33종목 → 2021-03월 이후 1,533~1,760종목으로 개선됨을 실측 확인.

## 미완료 (지침서 섹션 6 순서대로)

- [ ] 6-1. **04번 튜닝 전/후 실행시간 실측** — 현재는 OOM 방지용 캐싱만 적용된 상태. 브로드캐스트조인/파티션수/캐싱 튜닝의 성능 비교 목적 정식 측정은 미진행. **튜닝 전 수치를 반드시 먼저 실측**(나중에 추정 금지)
- [ ] 6-2. **워커 2대 vs 4대 스케일링 벤치마크** — 워커 3/4(각 1G) 추가를 시도했으나 호스트 메모리(7.7GB) 대비 워커 6GB + 드라이버 3GB 과다 할당으로 Docker Desktop/WSL2가 응답 불가에 빠짐(2회). 정식 비교값 없이 중단, 워커 3/4는 컨테이너 및 `docker-compose.yml` 정의 모두 제거하고 평소 체제(워커 1/2, 각 2코어/2GB)로 복귀. **재시도 시 워커 메모리 총합 + 드라이버 메모리가 호스트 총 메모리를 넘지 않도록 사전 계산할 것**
- [ ] 7. `jobs/05_export_to_mysql.py` — Spark JDBC writer로 `backtest_results`, `strategy_performance` 테이블 upsert
- [ ] 8. `docs/PERFORMANCE.md`, `docs/ARCHITECTURE.md`, `docs/VALIDATION.md` — 진행하면서 계속 갱신 (마지막에 몰아쓰지 않기)

## 미해결 코드 이슈 (2026-07-30 코드 리뷰에서 발견, 아직 안 고침)

### [심각] 03번 PER/PBR에 룩어헤드 바이어스
`main()`이 `prices_current`를 연도 필터 없이 `join_latest_price`에 넘기는데, 이 함수는 데이터셋 **전체에서 가장 최근 bas_dt** 하나를 고른다. 즉 `--year 2021`로 실행해도 `per`/`pbr`이 **2023-12 종가**로 계산되고, 04번은 이 값으로 2021년 시점 스크리닝을 한다. `rcept_no <= rebalance_date`가 재무제표 쪽 미래 정보는 막았지만 **비율의 분자인 가격은 안 막혀 있다.**

- "PER 낮은 순 상위 N"이 실질적으로 "2023-12 기준으로 싼 종목"(= 3년간 많이 떨어질 종목)을 2021년에 매수하는 셈이 됨
- 1,000개 전략 평균 -48%가 전체 종목 중앙값 -21.8%보다 훨씬 나빴던 것과 방향이 맞음 — **다만 인과관계는 가설이고 실측 검증 필요**
- verify_indicators에서 안 걸린 이유: Java도 최신가를 쓰므로(실시간 서비스로는 맞는 동작) 대조하면 당연히 일치함
- 03번 docstring "한계" 목록에 모멘텀 기준일 얘기는 있으나 PER/PBR 가격 기준일 얘기는 빠져 있음
- 2023년 단일 연도만 있을 때는 정상이었다가 다년치 확장으로 드러난 것 — fan-out 버그와 발생 조건이 동일
- **수정 방향(설계 변경)**: 03번은 `eps`/`bps`만 내보내고, 04번이 리밸런싱 시점 종가로 `per = price/eps`, `pbr = price/bps`를 직접 계산. 이래야 "가격은 매월 재평가"가 스크리닝에도 실제로 적용됨. 03→04 전체 재실행 및 결과 재해석 필요

### [중간] 04번이 02번의 플래그를 전혀 사용하지 않음
`is_interpolated` / `split_suspected`를 04번이 한 번도 읽지 않는다.

- 02번이 전 종목 × 전 거래일 격자를 forward-fill하므로 **상장폐지 종목도 마지막 가격이 끝까지 유지**됨. 04번은 "rebalance_date 이하 최신 bas_dt"를 쓰므로 상폐 종목을 그 가격으로 계속 보유한 것으로 계산 → **상폐 손실이 수익률 0%로 기록**됨
- 종목 101140 건: 02번은 `lag` 기준 **일별** 판정이라 급등일에는 `split_suspected`가 True로 찍혔을 가능성이 큼. 04번은 월말 시점만 보고 구간 사이 플래그를 확인하지 않음 → "02번 탐지가 놓쳤다"보다 **"04번이 플래그를 안 읽는다"** 쪽이 원인일 수 있음. 코드만으로는 단정 불가, 실측 확인 필요

### [낮음]
- `04:168` `F.log(period_return + 1.0)` — `period_return <= -1`이면 NaN이 되어 해당 전략의 이후 누적이 전부 오염
- `01:287` `companies`만 `mode("overwrite")` + `partitionBy("bas_dt")`(static) — 다른 `--bas-dt`로 재실행하면 이전 파티션이 지워짐. prices/financials/dividends는 전부 `append`인데 여기만 다름
- `03:248` `bps`에 `share_count != 0` 체크 없음(`eps`에는 있음). Spark가 null을 반환해 크래시는 나지 않음

## 다음 세션에서 이어갈 것

0. **04번 재실행 후 성과 수치 재확인** — `final_cum_return` 비결정성/MDD 시작점 수정이 기존 결과(예: KOSPI 최고 +72.6%, monthly -64.6%)를 얼마나 바꾸는지 대조
1. **03번 PER/PBR 룩어헤드 실측 검증** — `indicators`의 `per`이 year=2021/2022/2023에서 종목별로 동일한 값인지 확인(동일하면 위 "미해결 코드 이슈" 확정). 확정 시 수정 범위 결정
2. `check_04_02_backtest.ipynb` 최종 디버깅 완료 (벡터화 버전 실행 검증)
3. 종목 `101140`의 `split_suspected`가 급등일에 True로 찍혔는지 일별 조회 — 04번이 플래그를 안 읽는 게 원인인지 확인
4. 워커 2대 vs 4대 성능 벤치마크 재시도 (메모리 배분 재계산 후)
5. `conf/strategies.yaml`에 n=ALL 전략 영구 추가 여부 결정
6. `jobs/05_export_to_mysql.py`, `docs/` 3종 작성

## 운영상 유의사항 (실측으로 확인된 것만)

- `spark-submit`에 `--properties-file /opt/spark-apps/conf/spark-defaults.conf`를 빠뜨리면 클러스터 모드가 아닌 드라이버 로컬 모드로 실행되어 워커 분산이 전혀 안 됨
- `mode("overwrite")+partitionBy(...)`로 연도별 실행 시 `--conf spark.sql.sources.partitionOverwriteMode=dynamic` 필수 (기본 static 모드는 출력 디렉토리 전체를 지움)
- 드라이버 메모리는 `--conf spark.driver.memory`가 아니라 `spark-submit --driver-memory 3g` CLI 플래그로만 반영됨 (클라이언트 모드는 SparkContext 생성 이후 `--conf` 주입이 안 먹음)
- Spark가 모든 애플리케이션 소요시간(`Duration`)을 마스터 UI(`localhost:8088`)에 자동 기록하므로 `time` 명령이 불필요. REST API `/api/v1/applications`로도 조회 가능
- Jupyter 노트북 커널을 탭만 닫고 종료하지 않으면 클러스터 코어를 계속 점유해 다른 잡이 대기 상태에 빠짐 → `spark.stop()` 또는 Kernel Shut Down
- `.ipynb`를 코드로 직접 수정한 뒤에는 Jupyter 탭을 닫았다 새로 열 것. 브라우저 탭이 이전 버전을 물고 있으면 저장 시 디스크의 최신 수정사항이 통째로 덮어써짐(2회 발생). `.ipynb`는 `Edit`이 아닌 `NotebookEdit`으로 셀 단위 수정
- Jupyter 컨테이너에서 `docker exec`로 `pyspark`를 쓰려면 `PYTHONPATH=/usr/local/spark/python/lib/py4j-*.zip:/usr/local/spark/python`을 별도 지정해야 함 (Jupyter 서버가 커널을 띄울 때만 주입되는 값)
- 컨테이너 상태를 바꾸기 전에 `docker inspect <container> --format '{{json .Mounts}}'`로 실제 마운트 경로가 `Apache-Spark\valuepick-batch`인지 확인할 것 (이전 세션의 마운트 혼동으로 데이터 폴더를 삭제한 사고가 있었음)
- Windows Git Bash에서 `docker exec`에 절대경로 인자를 쓸 때는 `MSYS_NO_PATHCONV=1`을 앞에 붙일 것
- Docker Desktop이 리소스 압박으로 응답 불가에 빠져도 컨테이너는 정지 상태로 남을 뿐 삭제되지 않음 (`docker stop` ≠ `docker rm`)

## 하지 말아야 할 것 (지침서 7항)
- 기존 ValuePick의 Entity/DTO/스케줄러 직접 수정 금지
- MySQL을 Spark 잡 간 중간 데이터 전달 용도로 사용 금지 (전부 Parquet)
- 리밸런싱 시점 이후 공시된 재무 데이터 사용 금지 (룩어헤드 바이어스)
- 튜닝 먼저 적용하고 "전" 수치를 나중에 추정해서 기록 금지