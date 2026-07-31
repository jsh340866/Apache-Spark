# PERFORMANCE.md — 성능 실측

이 문서는 valuepick-batch에서 실제로 측정한 성능 수치만 다룬다. 추정치는 만들지 않으며,
기록되지 않은 수치는 "기록되지 않음"으로 명시한다. 구조 설명은 `ARCHITECTURE.md`, 검증
이력은 `VALIDATION.md` 참고.

## 1. 04번 계획 트리(Plan Tree) 폭발과 벡터화 효과

`run_for_rebalance_group()`이 리밸런싱 시점마다(monthly 36개, quarterly 12개)
`screen_portfolio()`를 파이썬 for문으로 반복 호출해 `unionByName`으로 이어붙이는 구조였을
때, 매 union마다 Catalyst 옵티마이저가 전체 실행계획을 재분석해야 해서 계획 길이가
시점 수보다 빠르게(초선형으로) 커졌다.

**실측치**: 시점 1개 → 12개로 늘렸을 때 계획 길이 32,218자 → 1,010,833자. 시점 수는
12배 늘었는데 계획 길이는 약 31배(정비례 대비 2.6배 더 큰 증가폭) 늘었다.

이 비용은 워커가 아니라 드라이버 혼자 부담한다. 실제 재현 시 드라이버 `-Xmx4g`인데 5.04GB를
사용해 OOM, CPU 611%(GC만 도는 상태)였고, 워커는 2.5GB/3GB로 놀고 있었다(실행 단계까지
가지도 못함) — executor 메모리를 늘리는 튜닝으로는 해결이 안 되는 문제였다는 것을 실측으로
확인했다.

`crossJoin` + `Window.partitionBy(..., "rebalance_date")` 벡터화 이후: 시점 6.5배 증가에
계획 길이는 3.5%만 증가했다(32배 증가하던 것 대비 결정적 개선). 벡터화된 함수의 구조와 왜
안전한지는 `ARCHITECTURE.md` 4절 참고.

## 2. 21,870개 전략 전체 실행 — 셔플 파티션 수 문제

가치주 점수 전환 후 그리드가 1,000개 → 21,870개(약 22배)로 커진 상태에서 전체 실행을
시도했다.

### 최초 시도 — shuffle.partitions=4로 실패

기존 1,000개 그리드 기준값(`spark-defaults.conf`의 `spark.sql.shuffle.partitions=4`)을
그대로 두고 실행했다. 포트폴리오 랭킹 stage(`Window.partitionBy("name","rebalance_date")`)
하나에서 태스크 0/4가 20분 넘게 완료되지 않았고, Spark REST API
(`/api/v1/applications/.../stages`)로 확인한 결과 `memoryBytesSpilled` 약 277GB,
`diskBytesSpilled` 약 68GB까지 쌓이는 것을 실측 확인한 뒤 kill했다.

**원인**: 전략 수가 1,000→21,870개(약 22배)로 늘었는데 셔플 파티션 수(4)는 그대로라,
crossJoin 이후 커진 데이터가 파티션 4개에 몰려 executor 메모리(2g)를 초과했다.

### 재시도 — shuffle.partitions=64로 성공

`spark-defaults.conf` 자체는 건드리지 않고, 이 실행에만
`--conf spark.sql.shuffle.partitions=64`로 override해 재실행했다. 태스크가 정상적으로
완료되며 진행되는 것을 재시도 로그로 확인했고, 최종 성공했다.

결과: `data/backtest_results_score_all/{summary,period_returns}`. summary 21,870행(name
전부 유일), period_returns 503,010행, null 없음.

**정확한 총 소요시간은 기록되지 않음** — PROGRESS.md에 성공 여부와 산출 결과 수치는
남아있으나, 이 실행 자체의 정확한 시작~종료 시각/총 소요시간은 명시돼 있지 않다.

## 3. 1,000개(threshold 그리드) vs 21,870개(점수 그리드) 실행시간 비교 — 비교 가능 범위 명시

기존 1,000개 전략(PER/PBR/배당 문턱값 그리드, 벡터화+이상치 제외 반영 후) 실행은
**1분 7초, 에러 없음**으로 기록돼 있다(`shuffle.partitions=4` 조건).

21,870개(점수 방식) 실행은 2절에서 설명한 대로 `shuffle.partitions=64`로 override한
조건에서 성공했고, 정확한 총 소요시간은 기록되지 않았다.

**비교할 수 있는 것과 없는 것을 구분해야 한다**: 두 실행은 셔플 파티션 수가 다르다
(4 vs 64). "전략 수가 22배 늘었는데 시간이 몇 배 걸렸다"는 식의 단순 비교는 두 가지 변수
(그리드 규모, 셔플 파티션 수)가 동시에 바뀐 상태를 비교하는 것이라 오해를 부른다.
- 비교 가능한 것: 1,000개 그리드가 `shuffle.partitions=4`에서 1분 7초에 성공했다는 사실,
  21,870개 그리드가 같은 `shuffle.partitions=4`에서는 20분 넘게 태스크가 멈추고
  대량 스필(277GB)이 발생해 kill했다는 사실 — 즉 "그리드 규모에 셔플 파티션 수를 맞추지
  않으면 실패한다"는 것.
- 비교 불가능한 것: 21,870개 그리드가 만약 `shuffle.partitions=4`로 끝까지 완주했다면
  걸렸을 시간(애초에 완주하지 못했으므로 알 수 없음), 그리고 21,870개 실행의 정확한 소요
  시간과 1,000개 실행의 1분 7초를 22배 배율로 직접 대조하는 것(파티션 수 조건 자체가
  다르므로).

## 4. shuffle.partitions 조정이 그리드 규모에 따라 필요하다는 교훈

`spark-defaults.conf`의 기본값 `spark.sql.shuffle.partitions=4`는 워커 총 4코어(2코어×2대)
기준으로, 1,000개 규모의 그리드를 가정해 설정된 값이다. 전략 수가 21,870개로 약 22배
늘어나자 이 값이 더 이상 맞지 않았다 — crossJoin 이후 데이터량은 늘었는데 셔플 파티션은
그대로 4개라, 각 파티션에 담기는 데이터가 executor 메모리(2g)를 초과해 스필이 발생했다.

`--conf spark.sql.shuffle.partitions=64`는 이번 21,870개 그리드에서 유효했던 값이지
일반 공식이 아니다 — 그리드 규모가 다시 바뀌면 이 값도 재검토가 필요하다.

## 5. 워커 2대 vs 4대 스케일링 벤치마크 — 시도 후 중단, 재개 안 함

워커 3/4(각 1G)를 추가해 2대→4대 스케일링 효과를 실측하려 시도했으나, 호스트 메모리
(7.7GB) 대비 워커 6GB(2G×3대분 추가) + 드라이버 3GB 과다 할당으로 Docker Desktop/WSL2가
응답 불가 상태에 빠졌다(2회 발생). 정식 비교값은 얻지 못한 채 시도를 중단했고, 워커 3/4는
컨테이너 및 `docker-compose.yml` 정의 모두 제거하고 평소 체제(워커 1/2대, 각 2코어/2GB)로
복귀했다.

이 벤치마크는 **더 이상 진행하지 않기로 결정됐다**(2026-08-01 사용자 결정). 과거 시도
이력은 기록으로만 남아 있으며, 재개 계획은 없다.

## 6. History Server(spark-history, 18080 포트) 도입 배경

전 세션에서 04번이 3연속 OOM으로 실패했을 때 "어디서 멈췄는지" 확인할 수단이 없었던 문제를
해결하기 위해 추가했다. 드라이버 UI(4040)는 잡 프로세스가 죽으면 같이 사라지므로, 실패
원인을 사후에 규명하려면 실행 중 자동으로 남는 로그가 필요했다.

`docker-compose.yml`의 `spark-history` 서비스와 `spark.eventLog.enabled=true` +
`spark.eventLog.dir`(마운트된 `logs/events`)을 추가해, 컨테이너가 재생성돼도 이벤트 로그가
보존되도록 했다. `spark-submit`의 stdout은 별도로 `logs/*.log`에 저장한다(gitignore
대상, 재생성 가능한 산출물이라 커밋하지 않음). 2절의 "태스크 0/4가 20분 넘게 멈추고
memoryBytesSpilled 277GB까지 쌓인" 상황을 Spark REST API로 실측 확인할 수 있었던 것도
이 인프라 덕분이다.
