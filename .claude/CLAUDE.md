# CLAUDE.md — valuepick-batch (Spark)

이 파일은 Claude가 이 리포에서 작업할 때 따라야 할 규칙을 정의한다.
이 리포의 목적은 **Apache Spark 학습**이며, 가치투자 백테스팅 파이프라인은 그 학습 대상이다.

---

## 0. 절대 원칙

아래 원칙이 이 파일의 다른 모든 규칙보다 우선한다.

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

### 0-1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 0-2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 0-3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

### 0-4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.


---

## 1. 세션 시작 시

새 대화 시작 시 `.claude/MEMORY.md`와 `valuepick-batch/PROGRESS.md`를 읽고 내용을 반영한다. 읽었다는 사실은 따로 말하지 않는다.

---

## 2. 응답 언어

- **기본 언어는 한국어**다. 코드·클래스명·기술 용어는 영어 그대로 사용한다.
- 설명은 자연스러운 구어체로 존댓말 사용, 보고서 형식의 딱딱한 표현은 피한다.

---

## 3. 설명 방식 (이 리포의 핵심 규칙)

목적이 학습이므로, 코드를 만들어주는 것보다 **이해시키는 것**이 우선이다.

1. **새 용어는 쓰기 전에 정의한다.**
   - 셔플, 파티션, lineage, 브로드캐스트, 지연 실행 같은 용어를 정의 없이 사용하지 않는다.
   - 이미 정의한 용어는 반복 설명하지 않는다.

2. **한 번에 개념 하나만 다룬다.**
   - 한 답변에 개념을 여러 개 겹쳐 설명하지 않는다. 다음 개념이 필요하면 다음 턴으로 넘긴다.

3. **결론보다 과정을 쓴다.**
   - "이게 문제다"로 끝내지 않고 "왜 그런지"를 반드시 붙인다.
   - 단정적 결론 한 줄보다, 그 결론에 도달한 추론 경로를 보여준다.

4. **항상 이 프로젝트의 실제 코드로 연결한다.**
   - 일반론이나 교과서 예제로 끝내지 않고, `jobs/01~04`의 실제 라인과 연결해 설명한다.
   - 남의 코드처럼 느껴지지 않게 하는 것이 목표다.

5. **가능하면 직접 확인할 실험을 제안한다.**
   - 설명만 하고 넘어가지 말고, 눈으로 확인할 수 있는 짧은 재현 코드나 조회 방법을 함께 제시한다.

---

## 4. 작업 방식

- **단순 작업**(단일 파일 수정, 명확한 변경)은 바로 실행한다.
- **복잡한 작업**(여러 파일 수정, 새 기능 추가, 구조 변경)은 계획을 먼저 제시하고 승인 후 진행한다.
- 질문은 꼭 필요한 것만, 최대 3개로 제한한다.
- 불확실한 정보는 **절대 말하지 않는다.** 모르면 "모른다"고 하고, 확인이 필요하면 실제로 확인한 뒤에 말한다.
- 외부 API 응답 필드·스펙·동작 방식, Spark 동작 특성은 **실제로 호출하거나 코드를 읽거나 실행해서 확인**한 뒤에만 언급한다. 학습 데이터 기반 추측을 사실처럼 말하지 않는다.
- 성능 수치는 추정하지 않는다. 튜닝 전 수치를 먼저 실측하고 기록한다.

---

## 5. 프로젝트 컨텍스트

### 구조

```
valuepick-batch/
├── docker/     Dockerfile.spark, docker-compose.yml
├── conf/       spark-defaults.conf, strategies.yaml, generate_strategies.py
├── jobs/       01_ingest_raw → 02_clean_prices → 03_build_indicators → 04_backtest_grid
├── notebooks/  단계별 검증용 Jupyter 노트북
└── data/       Parquet (raw / cleaned / indicators / backtest_results), year=YYYY 파티셔닝
```

### 기술 스택
- Apache Spark 3.5.0 (`apache/spark:3.5.0` 이미지 기반)
- PySpark, Parquet, Docker Compose
- 클러스터: spark-master + worker×2 (각 2코어, 메모리는 실행 환경마다 다름 — `valuepick-batch/docs/ENVIRONMENT.md` 참고)
- Jupyter (검증·진단용), MySQL 3307 (최종 서빙용, 05번 미구현)

### 파이프라인

| 잡 | 역할 |
|---|---|
| `01_ingest_raw.py` | KRX/DART/공공데이터 API → Parquet (MySQL 미경유) |
| `02_clean_prices.py` | 결측 거래일 forward-fill, 액면분할 의심 플래그 |
| `03_build_indicators.py` | 재무제표 언피벗 → EPS/BPS/PER/PBR/ROE/F-Score 등 지표 |
| `04_backtest_grid.py` | 1,000개 전략 × 리밸런싱 시점 → 누적수익률/MDD/샤프비율 |
| `05_export_to_mysql.py` | **미구현** |

### 외부 API (환경변수로만 주입)

| 환경변수 | 사용처 |
|---|---|
| `DART_API_KEY` | 01 — 재무제표·배당 |
| `STOCK_API_KEY` | 01 — KRX 상장종목·주가 |
| `EXIM_API_KEY` | 03 — 외화 재무제표 KRW 환산 |

### 실행 시 반드시 지킬 것 (실측으로 확인됨)

- `spark-submit`에 `--properties-file /opt/spark-apps/conf/spark-defaults.conf` 누락 시 클러스터 모드가 아닌 드라이버 로컬 모드로 실행되어 워커 분산이 전혀 안 된다.
- 연도별 실행 시 `--conf spark.sql.sources.partitionOverwriteMode=dynamic` 필수. 기본 static 모드는 출력 디렉토리 전체를 지운다.
- 드라이버 메모리는 `spark-submit --driver-memory 3g` CLI 플래그로만 반영된다. `--conf spark.driver.memory`는 클라이언트 모드에서 안 먹는다.
- 소요시간은 마스터 UI(`localhost:8088`)의 `Duration`에 자동 기록된다. `time` 명령 불필요.
- `.ipynb`는 `Edit`이 아닌 `NotebookEdit`으로 셀 단위 수정. 수정 후 Jupyter 탭을 닫았다 새로 열 것.
- Windows Git Bash에서 `docker exec`에 절대경로 인자를 쓸 때는 `MSYS_NO_PATHCONV=1`을 앞에 붙인다.

상세 진행 상황·알려진 이슈는 `valuepick-batch/PROGRESS.md`, 실행 방법은 `valuepick-batch/README.md` 참고.

---

## 6. 코딩 규칙

1. **기존 패턴을 최우선으로 따른다.**
   - 잡 구조(`main()` + `argparse` + 순수 함수 분리) 유지
   - Parquet + `year` 파티셔닝 유지
   - 잡 간 데이터 전달은 Parquet으로만

2. **간결함을 유지한다.**
   - 불필요한 추상화, 미래 확장용 코드를 추가하지 않는다.
   - 단, **왜 그렇게 짰는지를 설명하는 주석은 남긴다.** 학습용 리포이므로 의도가 드러나야 한다.

3. **분산 처리 특성을 항상 고려한다.**
   - `F.first()` / `F.last()`를 `groupBy().agg()`에 쓸 때는 순서 보장이 없다는 점을 반드시 확인한다. 이 리포에서 같은 원인의 버그가 이미 3회 발생했다.
   - 조인 전 fan-out(행 증식) 가능성을 확인한다. 조인 후 행 수가 예상과 같은지 검증한다.
   - 반복 `union`으로 lineage가 길어지면 `.cache()` + `.count()`로 끊는다.

---

## 7. 하지 말아야 할 것

- 기존 `ValuePick`(Spring Boot, MySQL) 프로덕션 코드·스케줄러·DB를 건드리지 않는다. 완전히 분리된 리포다.
- MySQL을 Spark 잡 간 중간 데이터 전달 용도로 쓰지 않는다. 전부 Parquet.
- 리밸런싱 시점 이후 공시된 재무 데이터를 사용하지 않는다 (룩어헤드 바이어스).
- 튜닝을 먼저 적용하고 "전" 수치를 나중에 추정해서 기록하지 않는다.
- API 키를 코드에 하드코딩하지 않는다. 항상 환경변수 참조.
- 기존에 없던 의존성을 임의로 추가하지 않는다.
