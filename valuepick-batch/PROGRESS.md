# 작업 진행 체크리스트 (2026-07-31 갱신)

새 세션에서 이어서 작업할 때 참고. 전체 설계 원본은 `../spark프로젝트/PROJECT_INSTRUCTIONS.md`.

**[최우선] 다음 세션은 ValuePick 가치주 점수 전환 작업부터.** 상세 계획:
`docs/PLAN_가치주점수_전환_20260731.md`. 04번을 지금의 PER/PBR/배당 문턱값 그리드에서
기존 ValuePick(`Top100Service.scoreAll()`)과 동일한 7팩터 백분위 가중합산 점수 기반으로
전환한다 — 지금까지 04번으로 찾은 "최선의 조합"은 ValuePick 실제 추천 로직과 무관한
별개 실험이었다는 게 2026-07-31 세션 말미에 확인됨.

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
- 상세 내용: `../spark프로젝트/작업요약_Spark배치_01-03구현및검증_20260729.md` 참고

### conf/strategies.yaml
- [x] `conf/generate_strategies.py` — PER 5 x PBR 5 x 배당수익률 4 x 리밸런싱주기 2 x 보유종목수 5 = 1,000개 조합 자동 생성
- [x] 파라미터 범위(07-31 기준 최신): PER(8/10/12/15/20), PBR(0.8/1.0/1.2/1.5/2.0), 배당수익률하한(null/1.0/2.0/3.0), 주기(monthly/quarterly), 보유종목수(10/30/50/**300**) — 보유종목수 20은 300으로 교체됨(9999 시도 시 OOM 발생해 300으로 타협, `conf/generate_strategies.py` 주석 참고)

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

## 해결된 이슈 (07-30에 발견, 07-30~07-31에 수정 완료)

### [해결] 03번 PER/PBR 룩어헤드 바이어스
07-30에 발견했던 대로 확정된 버그였음. **07-30 저녁에 수정 완료**: 03번은 `eps`/`bps`/
`net_income_krw`/`equity_krw`/`dividend_amount` 원본만 내보내고, 04번의
`compute_point_in_time_ratios()`가 각 리밸런싱 시점의 실제 종가로 `per_t`/`pbr_t`/
`dividend_yield_t`를 매번 다시 계산하도록 재작성함. 03번이 여전히 계산해서 내보내는
`per`/`pbr` 컬럼은 최신가 고정값(룩어헤드됨)이라 **`verify_indicators.ipynb` 대조검증용으로만
의도적으로 유지**, 04번은 이 컬럼을 쓰지 않는다.

### [해결] 04번이 02번의 split_suspected 플래그를 전혀 안 읽던 문제
2026-07-31 세션에서 최종 확정: 02번의 `flag_split_suspects()`는 종목 101140의 2023-10-23
급등(467원→9,340원, +1900%)을 **정확히 탐지**했다(하루 등락률 기준이라 정상 탐지). 문제는
04번이 `split_suspected` 컬럼을 **한 번도 참조하지 않았던 것** — 탐지는 됐지만 필터링에
연결이 안 된 상태로 방치돼 있었다. `calculate_period_returns()`에 `split_flags` 파라미터를
추가해 보유 구간 안에 플래그가 있는 종목의 수익률을 그 구간에서 제외하도록 수정, 실측 검증
완료(n30 이상 100개 전략, 1,650개 (전략,구간) 조합에서 값 변경 확인). 상세: 아래 04번 섹션.

## 07-31 세션 후반: 점수 전환 착수

`docs/PLAN_가치주점수_전환_20260731.md` 실행 시작. 확정된 세부 결정:
- 그리드는 `리밸런싱주기(2) × portfolio_size(5) × 가중치프리셋(N)`으로 확장 유지 —
  점수 방식으로 바뀌어도 "전략을 곱하면 연산량이 폭증한다"는 이 리포의 학습 목표를 계속
  실측하기 위해, 가중치 프리셋 자체도 7개 팩터 각각 후보값의 곱 조합(원래 04번과 같은 결)으로 구성
- 단, 이 그리드 탐색으로 나오는 "최고 성과 가중치"는 **2021~2023 KOSPI 표본 안에서의 사후 최적일 뿐**
  — 표본 밖 일반화는 보장 안 됨. 결과 서술 시 "이상적/최적 가중치"라 쓰지 말고 "이 표본 최고 성과
  조합"이라고 명시할 것 (2026-07-31 사용자와 확인)
- 04번 원본은 `jobs/04_backtest_grid_threshold.py`로, 그리드 생성기는
  `conf/generate_strategies_threshold.py`/`conf/strategies_threshold.yaml`로 복사 보존 후
  원본(`jobs/04_backtest_grid.py`, `conf/generate_strategies.py`, `conf/strategies.yaml`)을
  점수 방식으로 직접 수정하는 방향으로 진행 중

### 1단계: `01_ingest_raw.py`에 induty_code 수집 추가 — 코드 작성 완료, 실행은 아직
- `fetch_company_induty_code()`(DART company.json 호출), `existing_induty_codes()`(종목 단위
  재호출 방지 캐시) 추가, `main()`에 "1-1단계"로 편입. `--dart-limit`과 무관하게 전 종목 대상
- companies가 매번 `overwrite`라 기존 `already_ingested()`(파티션 단위)가 안 맞아서
  종목 단위 캐시를 새로 추가함 (기존 두 헬퍼 어느 것도 이 케이스에 안 맞아 최소 추가로 판단)
- **미실행**: 전체 2,555종목 API 재호출이라 시간이 걸려 다음에 별도로 실행 예정. 실행 전
  일일 호출 한도(40,000건) 대비 여유 있음을 재확인할 것

### 2~3단계: `04_backtest_grid.py` 점수 방식 재작성 — 코드 작성 완료, 실행은 아직 (2026-07-31)
- 금융업/F-Score 예외 판정은 **04번에서** 적용하기로 결정 (03번은 지표 계산 역할에 집중, 04번이
  스크리닝/점수 로직 전담)
- `apply_fscore_filter()` 추가: `induty_code` null 종목 제외, 앞 2자리 64/65/66(금융업)이면
  F-Score 필터 면제, 그 외 `f_score>=6`만 통과 — `Top100Service.candidates` 필터와 1:1 대응
  확인 완료. 단, Java 원본에 있던 `corp_cls=="Y"`(코스피 하드코딩)는 이식하지 않고 기존
  `--market` 옵션(기본 ALL)을 그대로 따름(사용자 확인)
- `add_percentile_rank()` 추가: `ascRank`/`descRank` + `percentileFraction((n-1-rank)/(n-1))`을
  `F.row_number()` 기반으로 재현. null은 불리한 쪽 극값(`+inf`/`-inf`)으로 채워 최하위 처리.
  `Window.partitionBy("name", "rebalance_date")`로 전략(가중치프리셋)×시점 단위 랭킹 — 기존
  04번의 벡터화 패턴(계획 조각 1개 고정) 그대로 재사용해 전략 수가 늘어도 구조는 안 바뀜
  - Java `Top100Service.scoreAll()`과 대조 검증 완료(가중치 PER25/PBR15/ROE20/ROA10/부채15/
    EPS성장률5/모멘텀10, 배당수익률은 원본도 점수 팩터로 안 씀)
- `screen_portfolio()`: `crossJoin(strategies)` 이후 팩터별 percentile × 전략 가중치 합산으로
  `value_score` 계산, `(name, rebalance_date)` 랭킹 후 `portfolio_size`만큼 선정
- `conf/generate_strategies.py`/`conf/strategies.yaml` 재작성: 리밸런싱(2) × portfolio_size
  (3/10/30/50/100, 5개) × 가중치프리셋(7팩터 각 3후보의 곱=3^7=2,187개, 원본±10%p단 EPS성장률만
  ±2%p, 프리셋마다 합 100으로 정규화) = **21,870개 전략**. 실행 검증: 21,870개 생성, name 전부
  유일, 가중치 합 전부 100(부동소수점 오차 이내) 확인
- 원본 문턱값 방식은 `jobs/04_backtest_grid_threshold.py` / `conf/generate_strategies_threshold.py`
  / `conf/strategies_threshold.yaml`로 이미 보존됨(수정 안 함)
### 실행 완료 (2026-07-31)
1. **01번 induty_code 수집 실행** — 전체 2,555종목 전부 induty_code non-null 확보 성공
   (`--bas-dt`/재무제표/가격은 기존 파티션 재사용이라 API 재호출 없이 induty_code만 신규 수집됨)
2. **03번 재실행** (2020~2023 4개년, currency 버그 수정 반영) — 2020년 2,195건, 2021~2023년 각
   2,368건으로 정상 완료
3. **04번 소규모 검증(30개 전략)** — 에러 없이 완료. F-Score 필터 검증: 2023년 기준 전체
   2,368종목 중 1,127종목 통과(금융업 예외 142종목 포함) — 필터가 과도하게 걸러내지도, 무의미하게
   다 통과시키지도 않는 합리적 비율. held_count도 n3/n10/n30/n50/n100 각각 목표치에 맞게 채워짐
   (초반 시점만 후보 부족으로 소폭 미달 — FY2020 공시 지연 현상과 일치, 기존에 알려진 패턴)
4. **04번 전체 21,870개 전략 실행 — 최초 시도 OOM성 스필로 재시도, 두 번째 시도 성공**
   - 최초 시도(`spark.sql.shuffle.partitions=4`, 기존 1,000개 그리드 기준값 그대로): stage
     하나(포트폴리오 랭킹, `Window.partitionBy("name","rebalance_date")`)에서 태스크 0/4가
     20분 넘게 완료되지 않고 `memoryBytesSpilled` 약 277GB, `diskBytesSpilled` 약 68GB까지
     쌓이는 것을 Spark REST API(`/api/v1/applications/.../stages`)로 실측 확인 후 kill
   - **원인**: 전략 수가 1,000→21,870개(약 22배)로 늘었는데 셔플 파티션 수(4)는 그대로라, crossJoin
     이후 커진 데이터가 파티션 4개에 몰려 executor 메모리(2g)를 초과
   - **조치**: `spark-defaults.conf`는 건드리지 않고, 이 실행에만
     `--conf spark.sql.shuffle.partitions=64`로 override해 재실행 → 태스크가 정상적으로
     완료되며 진행(재시도 로그로 확인), **최종 성공**
   - 결과: `data/backtest_results_score_all/{summary,period_returns}`. summary 21,870행(name
     전부 유일), period_returns 503,010행, null 없음. `final_cum_return` 범위 -64.1%~+85.8%,
     평균 -9.75%, 중앙값 -8.35% (2021~2023 하락장 반영 — 기존 문턱값 그리드 결과 해석과 궤 같음)
   - **주의**: `spark.sql.shuffle.partitions=64`는 지금 이 실행(21,870개 그리드)에서 유효했던
     값이지 일반 공식이 아님 — 그리드 규모가 또 바뀌면 이 값도 재검토 필요
5. **`notebooks/check_04_backtest.ipynb` 점수 방식으로 전면 재작성 완료 (2026-08-01)**
   - 기존(문턱값 그리드) 노트북은 `notebooks/check_04_backtest_threshold.ipynb`로 보존
   - 새 노트북: 상/하위 랭킹, monthly/quarterly 비교, 시계열, held_count, portfolio_size별
     비교는 기존 구조 유지. 섹션 9("PER/PBR/배당 조건별 n비교")는 점수 방식에 안 맞아
     "가중치프리셋별 성과 비교"로 교체 — `strategies.yaml`의 7개 가중치 값을 name으로 조인해
     상위/하위 프리셋의 실제 가중치 구성, ValuePick 원본 가중치 대비 증감, 팩터별 가중치와
     평균 성과의 상관계수까지 확인
   - **실행 중 발견한 환경 이슈**: `spark.createDataFrame(strategies_raw)`로 파이썬 리스트를
     워커로 보내는 연산이 `PYTHON_VERSION_MISMATCH`로 실패함 — Jupyter 컨테이너(드라이버)는
     Python 3.11, 워커 컨테이너는 Python 3.8로 마이너 버전이 다름. 기존 `check_04_backtest_
     threshold.ipynb`는 애초에 `spark.read.parquet`만 쓰고 `createDataFrame`을 쓴 적이 없어
     이 문제를 겪은 적이 없었던 것으로 확인. **해결**: `strategies.yaml` 조인 부분을 Spark
     연산이 아니라 `summary.toPandas()` 이후 pandas merge로 변경 — 21,870행 규모는 로컬
     pandas로 충분해 워커에 보낼 필요 자체가 없음. `jupyter nbconvert --execute`로 전체 셀
     끝까지 에러 없이 실행 검증 완료(상관계수 등 결과값도 확인함: weight_pbr 0.505로 가장
     강한 양의 상관, weight_momentum -0.362로 가장 강한 음의 상관 — 2021~2023 표본 한정)
   - **주의**: 이 파이썬 버전 불일치는 앞으로 다른 노트북에서 `spark.createDataFrame`으로
     파이썬 객체를 직접 워커에 보낼 때마다 재발할 수 있는 환경 이슈. `spark.read.parquet`
     처럼 워커가 파일을 직접 읽는 경로는 영향 없음
   - **재확인(2026-08-01)**: 사용자가 Jupyter 웹 UI에서 실제로 이 에러(`PYTHON_VERSION_MISMATCH`)를
     겪은 것을 확인 — CLI 검증 방식 탓이 아니라 실제로 존재하는 환경 문제임을 재확인했다.
     `spark-submit`(드라이버=워커=워커 이미지 Python 3.8)으로 동일 로직을 돌리면 문제없이
     성공하는 것도 실측 확인 — 즉 Jupyter 컨테이너(Python 3.11)와 워커 컨테이너(Python 3.8)의
     파이썬 버전 자체가 다른 게 근본 원인. 워커 이미지에 Python 3.11을 맞추려 시도했으나
     Ubuntu 20.04 기본 저장소에 3.11이 없고 deadsnakes PPA도 패키지 인덱스를 못 받아와 실패,
     `apache/spark:3.5.9-python3`(Ubuntu 22.04, Python 3.10) 대체도 검토했으나 여전히 Jupyter
     (3.11)와 마이너 버전이 안 맞아 근본 해결로 채택하지 않음. **최종: 노트북은 pandas
     merge 방식 유지, 워커/Jupyter 파이썬 버전 통일은 별도 이슈로 보류**
   - **[미해결 이슈] 워커(Python 3.8, apache/spark:3.5.0)와 Jupyter(Python 3.11,
     jupyter/pyspark-notebook:spark-3.5.0)의 파이썬 마이너 버전 불일치** — Jupyter 노트북에서
     `spark.createDataFrame()`으로 파이썬 객체를 직접 워커에 보내는 연산은 전부 이 문제를
     겪는다. `spark.read.parquet` 등 워커가 파일을 직접 읽는 경로는 영향 없음. 근본 해결(워커
     이미지에 Python 3.11 설치 또는 동일 Python 버전의 다른 베이스 이미지 채택)은 다음 세션에서
     별도로 진행 필요. 그때까지는 노트북에서 파이썬 리스트/dict를 Spark DataFrame으로 변환해야
     할 경우 `spark.read`로 파일을 거치거나 pandas로 우회할 것
6. **두 노트북에 "10. 결과 해석" 섹션 추가 (2026-08-01)** — 실제 실행 결과 수치를 근거로
   `check_04_backtest_threshold.ipynb`/`check_04_backtest.ipynb` 각각에 정리:
   - threshold: 리밸런싱 주기가 조건보다 영향이 큼(monthly +16.0% vs quarterly +3.4%),
     portfolio_size 커질수록 단조 개선(n10 9.6%→n300 25.8%, 분산 효과), 배당수익률 조건 있는
     전략이 없는 전략보다 뚜렷하게 우수, PER/PBR 상한 자체는 상대적으로 영향 작음
   - 점수 방식: monthly가 quarterly보다 우수(threshold와 같은 방향, 표본 자체 성격으로 추정),
     n3(소수 종목) 몰빵이 상/하위 20개 대부분을 차지(극단값), **weight_pbr 상관계수 0.505(최고
     양의 상관)/weight_momentum -0.362(최고 음의 상관)** — 이 표본(하락/횡보장)에서 저평가
     추구 팩터가 유리하고 추세추종 팩터가 불리했을 가능성. F-Score 필터 통과율 약 48%로 정상
   - 두 노트북 모두 "이 표본에서만 유효, 일반화 경고" 섹션을 명시
7. **다음에 할 일**: `--market KOSPI` 버전도 실행할지 결정

## 07-31 세션 핵심 작업: 04번 계획 트리(Plan Tree) 폭발 해결 + 이상치 수정

전 세션(07-30 밤~07-31 새벽)에서 04번이 3연속 OOM으로 실패한 뒤, 07-31 세션(학원→집 컴퓨터
이전 직후)에서 진짜 원인을 찾아 해결했다. 상세 경위는
`../spark프로젝트/작업요약_04번계획트리해결및이상치수정_20260731.md` 참고.

### 원인: 시점별 반복 union이 실행계획을 초선형으로 키움
`run_for_rebalance_group()`의 파이썬 for문이 리밸런싱 시점마다(monthly 36개, quarterly 12개)
`screen_portfolio()`를 반복 호출해 `unionByName`으로 이어붙이는 구조였음. 매 union마다
Catalyst 옵티마이저가 전체 트리를 재분석해 계획이 시점 수보다 빠르게(초선형) 커짐(실측:
시점 1개→12개, 계획 32,218자→1,010,833자, 정비례 대비 2.6배). 이 비용은 드라이버 혼자
부담 — 실제 재현 시 드라이버 `-Xmx4g`인데 5.04GB 사용, CPU 611%(GC만 도는 상태), 워커는
2.5GB/3GB로 놀고 있었음(실행 단계까지 못 감).

### 해결: prices_at_dates / latest_valid_indicators+screen_portfolio / calculate_period_returns 벡터화
시점(날짜) 목록을 파이썬 for문이 아니라 `spark.createDataFrame`으로 만들어 `crossJoin` 한
번으로 처리하도록 재작성. `Window.partitionBy`에 `rebalance_date`를 반드시 포함해야
서로 다른 시점 종목이 한 랭킹에 섞이지 않는다. 벡터화 후 계획 길이는 시점 6.5배 늘려도
3.5%만 증가(32배 증가 대비 결정적 개선). 각 함수 벡터화 시 기존 로직을 복제한 대조 스크립트로
행 수·값 완전 대조 검증(`logs/verify_*.py` — 코드 자체는 커밋 대상으로 남겨둠, 재사용 가능).

**검증 중 발견한 부수 버그**: `latest_valid_indicators`의 `orderBy(desc(rcept_no))`가 동순위
문제를 안고 있었음(같은 rcept_no에 당기/전기/전전기 3개년치가 응답에 함께 와 (stock_code,
rcept_no) 조합이 최대 3행 중복, 530개 조합 확인) — `orderBy(desc(rcept_no), desc(year))`로
타이브레이커 추가해 해결.

### 04번 재실행 성공 (07-31, 벡터화+이상치 제외 반영 후)
`--output-dir`을 `data/backtest_results_kospi_n300`으로 신규 분리(기존 `backtest_results/`,
`backtest_results_kospi_2w/`는 07-30 15:44 시점 산출물이라 룩어헤드 미수정 구버전 — **더 이상
참조하지 말 것**, PROGRESS.md의 옛 성과 수치(+72.6%, -48% 등)는 전부 이 구버전에서 나온 값).

**소요시간 1분 7초, 에러 없음.** 이상치(101140) 제외 반영 전/후 비교:

| | 이상치 포함 | 이상치 제외(현재) |
|---|---|---|
| n10 최고 누적수익률 | +193.2% | **+32.7%** |
| 상위권 성격 | `dynone`(배당조건 없음) 위주 | `dy1.0/2.0/3.0`(배당조건 있음) 위주 |

이상치 제외 반영 후 전체 1,000개 전략 중 최고 성과: `per20_pbr1.2_dy3.0_monthly_n300`
(+58.7%, 샤프비율 0.228로 전체 1위). 배당수익률 하한을 높일수록, n을 늘릴수록(분산 효과)
단조롭게 성과가 좋아지는 패턴 확인. **단, 이건 PER/PBR/배당 문턱값 그리드 안에서의 최선일
뿐 ValuePick 실제 추천 로직과는 무관 — 위 "가치주 점수 전환" 계획 참고.**

### History Server 도입 (실패 원인 규명 인프라)
전 세션 "실패해도 로그가 사라져서 원인 파악 불가"를 막기 위해 추가.
`docker-compose.yml`에 `spark-history` 서비스(18080 포트), `spark.eventLog.enabled=true` +
`spark.eventLog.dir`을 마운트된 `logs/events`로 설정(컨테이너 재생성해도 로그 보존).
`spark-submit` stdout은 `logs/*.log`로 저장(gitignore 대상, 재생성 가능).

## 미해결 코드 이슈

### [낮음]
- `04_backtest_grid.py:301` `F.log(period_return + 1.0)` — `period_return <= -1`이면 NaN이 되어 해당 전략의 이후 누적이 전부 오염
- `01_ingest_raw.py:299` `companies`만 `mode("overwrite")` + `partitionBy("bas_dt")`(static) — 다른 `--bas-dt`로 재실행하면 이전 파티션이 지워짐. prices/financials/dividends는 전부 `append`인데 여기만 다름
- `03_build_indicators.py:248` `bps`에 `share_count != 0` 체크 없음(`eps`에는 있음). Spark가 null을 반환해 크래시는 나지 않음

### [해결, 07-31] `pivot_financials`의 currency F.first() 비결정성
`(stock_code, fs_div, year)` 그룹 안에서 서로 다른 `rcept_no`(정정공시 등)가 서로 다른
표시통화를 쓰는 실제 사례 확인(예: 950190이 2018~2021은 HKD, 2022~2023은 USD). 기존 코드는
`earliest_rcept` 결정과 무관하게 `currency`만 따로 `F.first(ignorenulls=True)`로 뽑아
rcept_no와 currency가 다른 공시 출처에서 올 수 있었음. `earliest_rcept`로 결정된 그 rcept_no에
실제로 달린 currency를 조인해 가져오도록 수정, fan-out 없음(13,879행 유지) 검증 완료.
**03번 재실행은 아직 안 함 — 다음 세션에서 재실행 후 indicators 재검증 필요.**

## 다음 세션에서 이어갈 것

**완료됨(08-01 세션)**: ValuePick 가치주 점수 전환 전체(induty_code 수집 실행, 03번 재실행,
04번 점수 방식 재작성 및 21,870개 전략 ALL 시장 실행, 결과 검증 노트북 2종 결과 해석 작성).
아래는 남은 항목.

1. **KOSPI 버전 04번 실행 — 다음 세션(2026-08-02)에 진행하기로 결정**. 종목 수 사전 확인
   완료: 전체 2,555종목 중 KOSPI(corp_cls='Y') 810종목/KOSDAQ 1,745종목 — KOSPI만 필터링
   하면 종목 수가 약 1/3로 줄어 ALL 실행보다 빠를 것으로 예상(단, 정확한 배율은 미실측).
   실행 시 `--output-dir`을 `data/backtest_results_score_kospi` 등으로 ALL과 분리해서
   Parquet이 서로 안 겹치게 할 것
2. **05번(`jobs/05_export_to_mysql.py`) MySQL 테이블명을 market별로 분리** (2026-08-01) —
   기존엔 테이블명이 `strategy_performance`/`backtest_results`로 고정이라 다른 market으로
   재실행하면 이전 결과가 truncate로 지워지는 문제가 있었음. `--market` 값을 소문자
   접미사로 붙여 `strategy_performance_{all|kospi|kosdaq}`,
   `backtest_results_{all|kospi|kosdaq}`로 테이블 자체를 분리하도록 수정 — 같은 market
   재실행은 그 테이블만 truncate, 다른 market 테이블은 영향 없음. `market` 컬럼은 테이블
   안에서도 바로 구분되도록 그대로 유지. 기존 고정 이름 테이블(오늘 적재한 ALL 결과)은
   Parquet(`backtest_results_score_all`)에 동일 데이터가 남아있어 손실 없이 DROP TABLE로
   정리함 — 현재 MySQL은 테이블이 아예 없는 빈 상태. **다음 세션에서 KOSPI 04번 실행 후,
   05번을 `--market ALL`(Parquet은 이미 있음, `data/backtest_results_score_all`)과
   `--market KOSPI` 두 번 실행해서 `strategy_performance_all`/`_kospi`,
   `backtest_results_all`/`_kospi` 4개 테이블을 한 번에 채우기로 결정 (2026-08-01, 사용자
   지시 — ALL을 따로 먼저 넣지 않고 KOSPI와 같이 처리)**
### [완료] `docs/` 3종(ARCHITECTURE/VALIDATION/PERFORMANCE) + README.md 전면 재작성 (2026-08-01)
- `docs/ARCHITECTURE.md`(172줄): 파이프라인 개요, 클러스터 구성, Parquet 레이크 구조,
  04번 벡터화 설계(crossJoin+Window로 계획 조각 1개 고정), ValuePick과의 관계, 05번이
  MySQL을 쓰는 이유
- `docs/VALIDATION.md`(138줄): 03번 지표 검증, F.first()/F.last() 비결정성 버그 3건 묶음,
  04번 벡터화 대조검증(타이브레이커 버그 포함), 101140 이상치 처리, 점수 전환 후 검증,
  MySQL 적재 검증
- `docs/PERFORMANCE.md`(106줄): 계획 트리 폭발 실측(32,218자→1,010,833자), 21,870개 실행
  시 shuffle.partitions 튜닝, 1,000개 vs 21,870개 비교 시 무엇을 비교할 수 있는지 명시,
  워커 스케일링 벤치마크는 "중단, 재개 안 함"으로 서술
- `valuepick-batch/README.md` 전면 재작성 — 05번 미구현/04번 문턱값 그리드 기준의 낡은
  내용을 지금 상태(01~05번 전부 구현, 04번 점수 방식 21,870개 전략, 결과 시각화 노트북
  2종)로 갱신. 프로젝트 목적/아키텍처/파이프라인/실행법/결과 시각화/문서 링크 포함
- 세 문서 모두 PROGRESS.md/코드에 실제 기록된 사실만 사용, 추측 수치 없음(작성 에이전트가
  직접 검증 확인)

2. `jobs/05_export_to_mysql.py` 작성 완료 — 아래 항목 참고

### [완료] `jobs/05_export_to_mysql.py` 작성 및 실행 검증 (2026-08-01)
- 04번 출력(`summary`→`strategy_performance`, `period_returns`→`backtest_results`) 매핑,
  `mode("overwrite")+truncate` 방식(테이블 구조 유지, 데이터만 재삽입), `--market` 값을
  모든 행에 `market` 컬럼으로 채워 넣음. run_id 이력 관리는 과설계로 판단해 제외
- `docker-compose.yml`의 `spark-master` 서비스에 `MYSQL_ROOT_PASSWORD` 환경변수 추가
  (기존 `DART_API_KEY` 등과 같은 패턴), `spark-master` 컨테이너 재생성으로 반영 확인
- 실행 검증: `--input-dir data/backtest_results_score_all --market ALL`로 실제 적재 →
  `strategy_performance` 21,870행, `backtest_results` 503,010행 — Parquet 원본과 행 수 정확히 일치
- **[미해결 이슈] `name` 컬럼이 MySQL에서 `longtext`로 생성됨** — Spark JDBC writer가
  DataFrame의 string 타입을 스키마 추론 시 `LONGTEXT`로 매핑한 것으로 보인다(직접 원인 확인은
  안 함). `LONGTEXT`는 일반 인덱스를 바로 걸 수 없어(prefix 길이 지정 필요), 이후 기존
  ValuePick 프론트엔드/API가 이 테이블을 이름으로 조회하거나 조인해야 하면 성능 문제가 될 수
  있음. 필요해지면 `.option("createTableColumnTypes", "name VARCHAR(255)")` 같은 JDBC 옵션으로
  타입을 명시하는 방향 검토할 것

**하지 않기로 결정**: 워커 2대 vs 4대 스케일링 벤치마크는 더 이상 진행하지 않는다
(2026-08-01 사용자 결정). 과거 시도 이력(6-2 항목, 위 참고)은 기록으로만 남긴다.

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

## 하지 말아야 할 것 (지침서 8항)
- 기존 ValuePick의 Entity/DTO/스케줄러 직접 수정 금지
- MySQL을 Spark 잡 간 중간 데이터 전달 용도로 사용 금지 (전부 Parquet)
- 리밸런싱 시점 이후 공시된 재무 데이터 사용 금지 (룩어헤드 바이어스)
- 튜닝 먼저 적용하고 "전" 수치를 나중에 추정해서 기록 금지