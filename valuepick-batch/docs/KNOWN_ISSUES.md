# KNOWN_ISSUES.md — 알려진 미해결 이슈

이 문서는 발견했지만 아직 고치지 않은 문제를 기록한다. 이미 고친 버그의 이력은
`VALIDATION.md` 참고.

## 모멘텀 룩어헤드 바이어스 수정 — 코드는 완료, 재실행은 학원 PC 메모리로 막힘 (2026-08-04)

### 진행 상황 요약

아래 "모멘텀 룩어헤드 바이어스" 이슈를 고치는 작업을 진행했다. **코드 수정과 필요 데이터
백필은 끝났지만, 04번 전체 재실행이 이 컴퓨터(7.7GB)의 메모리 한도로 OOM 실패했다.**
집 컴퓨터(31.9GB)에서 이어서 재실행만 하면 된다.

1. **04_backtest_grid.py 수정 완료**: `months_ago()`, `momentum_prices_at_dates()` 함수를
   추가하고, `screen_portfolio()`가 03번의 고정 `momentum` 컬럼 대신 리밸런싱 시점 t마다
   t-1개월/t-12개월 종가를 `data/cleaned/prices`에서 다시 조회해 계산한 `momentum_t`를
   쓰도록 변경했다. `compute_point_in_time_ratios()`가 PER/PBR/배당수익률을 재계산하는 것과
   동일한 원칙. 03_build_indicators.py의 `momentum` 컬럼 자체는 건드리지 않았다(다른 소비자
   영향 없음). 소규모 검증(종목 950200)으로 리밸런싱 시점마다 momentum_t 값이 실제로 달라지는
   것을 확인함 — 03번 방식이었다면 전 시점에서 값이 동일했어야 함.

2. **2020년 일별 시세 백필 완료**: 모멘텀 t-12개월 계산에 필요한 2020년 시세가
   `data/cleaned/prices`에 전혀 없었다(01_ingest_raw.py가 2021~2023년만 수집했었음).
   2021년 리밸런싱 시점(monthly+quarterly)의 t-12개월 날짜를 역산하면 2020년 매월 말일
   12개만 있으면 충분하다는 걸 확인하고, `01_ingest_raw.py --price-start/--price-end`를
   각 월말 전후 7일 범위로 12번 나눠 실행해 `data/raw/prices`에 추가했다(--bas-dt는 companies
   overwrite를 피하려고 기존 성공 실행값 20260724로 고정, 재무제표/배당은 이미 있어 자동 스킵됨).
   이후 `02_clean_prices.py`를 재실행해 `data/cleaned/prices`에도 반영, 12개 목표 날짜 전부
   "그 날짜 이하 최근 거래일"로 정상 커버되는 것을 확인했다.

   **주의**: 01_ingest_raw.py는 날짜 범위를 다 수집한 뒤 루프 끝에서 한 번에 write하는 구조라,
   중간에 네트워크가 끊기면 그때까지 받은 데이터가 통째로 유실된다(실측: 2020년 전체 범위를
   한 번에 수집 시도했다가 `apis.data.go.kr` 연결이 끊겨 두 번 다 0건 저장으로 끝남). 그래서
   좁은 범위로 여러 번 나눠 실행하는 방식으로 우회했다. 이 구조 자체는 미해결 상태로 남아있다.

3. **04번 전체 재실행 → OOM 실패**: `docker exec spark-master spark-submit 04_backtest_grid.py`
   기본 인자로 실행. `[monthly] 리밸런싱 시점 36개` 로그까지는 정상 출력됐으나
   `run_for_rebalance_group`의 `portfolios.count()`(screen_portfolio 결과 실체화 -
   21,870전략 x 36시점 crossJoin 이후 Window 랭킹 단계)에서
   `java.lang.OutOfMemoryError: Java heap space`로 드라이버가 죽었다.

   **원인**: `conf/spark-defaults.conf`에 이미 다음 주석이 있었다 -
   `spark.executor.memory 2g` 설정 옆에 "[환경의존] 2g는 집 컴퓨터(호스트 31.9GB) 기준.
   학원 컴퓨터(7.7GB)에서는 1g로 되돌릴 것." 그런데 지금 이 컴퓨터가 `docker stats` 확인 결과
   컨테이너 메모리 한도 7.678GiB로, 정확히 "학원 컴퓨터" 사양과 일치한다. 즉 이 설정이 집
   컴퓨터용으로 남아있는 채로 학원 컴퓨터에서 실행된 상태였다. 게다가 `spark.driver.memory`가
   아예 지정되어 있지 않아 기본값(1g 안팎)에 머물렀고, 이번에 죽은 지점의 스택트레이스가
   `executor driver`(드라이버 자체)를 가리키는 것으로 봐서 드라이버 힙 부족이 직접 원인으로
   보인다(executor.memory 2g→1g 조정만으로 해결되지 않을 수 있음 - driver.memory도 같이
   확인 필요, 착수 전이라 미검증).

### 집 컴퓨터에서 진행한 작업 (2026-08-04)

1. **2020년 시세 백필 재현 완료**: `data/`가 `.gitignore`에 걸려 학원 PC의 백필 결과가 git으로
   전달되지 않는다는 걸 확인하고(위에서 예고한 대로), 집 컴퓨터에서 동일하게 재현했다.
   `01_ingest_raw.py --bas-dt 20260724 --year 2020 --price-start <월말-7일> --price-end <월말>`을
   2020년 1~12월 12번 나눠 실행(companies의 기존 파티션 `bas_dt=20260724`와 동일 값 사용,
   좁은 범위로 나눠 네트워크 중단 시 전체 유실을 피함). 12회 전부 정상 완료.
2. **`02_clean_prices.py`에 `--years` 인자 추가**: 기존엔 `data/raw/prices` 전체 연도를 그대로
   정제 대상으로 삼았는데, 이 리포의 raw에는 04번 백테스트 대상(2021~2023) 외에도 2020년 모멘텀
   백필분과 최신 `--bas-dt`(2026년) 재실행 스냅샷이 섞여 있어, 거래일 캘린더가 필요 이상으로
   넓어지는 문제가 있었다. `--years 2020,2021,2022,2023`(모멘텀 t-12개월 계산에 필요한 2020년
   포함, 04번 무관 연도인 2025/2026 제외)으로 지정해 재실행 — `data/cleaned/prices`의 2020~2023
   파티션만 dynamic overwrite로 갱신, 다른 연도 파티션은 그대로 유지됨을 확인.
3. **momentum_t 커버리지 재검증 완료**: 04번의 `build_rebalance_dates`가 만드는 monthly+quarterly
   매수 시점(유니크 35개) 전체에 대해 `months_ago(rd, 12)`/`months_ago(rd, 1)`를 계산해
   `data/cleaned/prices`의 실제 `bas_dt`와 대조 — **커버 실패 시점 0개**. 이전에 두 검증 에이전트가
   지적했던 "2021년 12개 시점 momentum 전부 null" 문제가 해소됨.
4. **"보간된 행 0건"이 버그가 아님을 실측으로 확인**: `01_ingest_raw.py`의 `fetch_krx_listed`가
   `--bas-dt`(2026년 시점) 기준 상장종목 목록으로 가격을 수집하다 보니, 2020~2023년 당시 아직
   상장 전이었던 종목의 그 구간은 raw 자체에 시세가 없다. forward-fill은 "종목이 처음 등장한 날짜
   이전" 구간을 채울 직전값이 없어 결측으로 남고, 02번의 "보간 불가 행 제거" 로직(107~110행)이
   이를 걸러낸다. 실측 결과 결측 121,564건 전부가 `bas_dt < first_seen`(최초 등장일 이전)이었고,
   그 이후 구간의 결측(진짜 거래정지 추정)은 0건 — 이 데이터셋엔 2021~2023년 구간 안에서 실제
   거래정지로 인한 결측이 없다는 뜻이며, 정상 결과임을 `notebooks/check_02_cleaned_prices.ipynb`에
   문서화했다(노트북도 재실행 반영 완료).
5. `conf/spark-defaults.conf`는 이미 집 컴퓨터 사양(executor 2g)으로 맞게 설정돼 있음을 확인.
   Docker Desktop이 WSL2에 할당한 메모리는 약 15.6GB(호스트 실물 31.9GB와 다름)로 확인됨.

### 남은 작업

`docker exec spark-master spark-submit --driver-memory 4g /opt/spark-apps/jobs/04_backtest_grid.py`로
ALL/KOSPI 전체 재실행(21,870개 전략 x 48시점)하면 momentum_t 기준 `data/backtest_results_score_all|kospi`가
재생성된다. 코드·데이터 준비는 위 1~4번으로 전부 끝난 상태라 이 명령만 실행하면 된다.
성공하면 README.md의 `weight_momentum -0.362` 상관계수 및 관련 해석 문구도 새 결과로 재검증/갱신 필요
(이 이슈의 "영향 범위" 섹션 참고).

## 모멘텀(momentum) 룩어헤드 바이어스 — 원본 이슈 기록 (2026-08-04)

### Problem

`03_build_indicators.py`의 `join_momentum()`이 `stock_code`만으로 조인해서, 연도
구분 없이 **모든 리밸런싱 시점에 동일한 모멘텀 값**을 붙인다.

```python
def join_momentum(df: DataFrame, prices_1m: DataFrame, prices_12m: DataFrame) -> DataFrame:
    p1m = _latest_snapshot(prices_1m).select("stock_code", ...)
    p12m = _latest_snapshot(prices_12m).select("stock_code", ...)
    df = df.join(p1m, on="stock_code", how="left").join(p12m, on="stock_code", how="left")
    ...
```

`_latest_snapshot()`은 종목별로 `bas_dt`가 가장 최신인 스냅샷 1건만 남긴다. 그런데
`01_ingest_raw.py`가 수집하는 `1m_ago`/`12m_ago` 스냅샷 자체가 "이 배치를 실행한
시점 기준" 1개월 전/12개월 전 가격이라, 03번이 2021년 지표를 만들든 2023년 지표를
만들든 **똑같은(가장 최근 실행 시점 기준) 모멘텀 값 하나**가 조인 키(`stock_code`)만
맞으면 전부 붙는다.

이 값은 `04_backtest_grid.py`의 `screen_portfolio()`에서 `momentum_pctl`로 랭킹화돼
`value_score`(7팩터 가중합산 점수)에 그대로 들어간다.

### 왜 PER/PBR/배당수익률과 다르게 남았는가

`04_backtest_grid.py`의 `compute_point_in_time_ratios()`는 정확히 이 유형의
문제(03번이 "최신" 가격 하나로 고정 계산하는 룩어헤드 바이어스)를 이미 인지하고
PER/PBR/배당수익률에 대해서는 리밸런싱 시점별로 재계산하도록 고쳐뒀다. 이게
가능했던 이유는 03번이 재계산에 필요한 원본 재무값(`net_income_krw`, `equity_krw`,
`dividend_amount`)을 함께 넘겨줬고, 04번은 이미 `prices_by_date`(전체 시점의 종가
시계열)를 갖고 있어서 그 시점 가격만 조인하면 됐기 때문이다.

반면 모멘텀은 "리밸런싱 시점 t 기준 1개월 전/12개월 전 종가"가 필요한데, 이건
`01_ingest_raw.py`가 애초에 "실행 시점 기준" 스냅샷 1개만 수집하는 구조라 시점별
과거 데이터 자체가 없다. `prices_by_date`(전체 일별 시세 시계열)에서 t-1개월,
t-12개월 날짜의 종가를 다시 찾아 조인하는 별도 로직이 필요한데, 이 작업이
빠진 채로 방치됐다.

### 영향 범위

- 21,870개 전략 전부가 이 momentum 값의 영향을 받는다(가중치 0인 전략 제외).
- 특히 모멘텀 가중치가 큰 전략일수록 이 왜곡의 영향이 크다. `factor-correlation`
  분석에서 모멘텀 가중치가 -0.362로 가장 강한 음의 상관을 보인 결과도, 이 룩어헤드
  바이어스가 섞인 채로 나온 수치라는 점을 감안해서 해석해야 한다.
- 2021~2023년 백테스트 전 구간에 걸쳐 모멘텀 값이 사실상 상수(종목별로 고정)이므로,
  같은 종목은 어느 시점에 스크리닝되든 동일한 momentum_pctl을 받는다. 즉 리밸런싱
  시점에 따라 모멘텀이 달라지는 실제 시장 상황을 전혀 반영하지 못한다.

### 고칠 방법 → 문서 상단 "모멘텀 룩어헤드 바이어스 수정" 섹션에서 착수함

당초 여기 적었던 방향(리밸런싱 시점 t마다 t-1개월/t-12개월 종가를 다시 조인해
04번에서 재계산)대로 실제 구현까지 진행했다. 진행 상황과 남은 작업은 이 문서
맨 위 섹션 참고.
