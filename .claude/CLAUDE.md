# CLAUDE.md — valuepick 백엔드

이 파일은 Claude가 valuepick 프로젝트에서 작업할 때 따라야 할 규칙을 정의한다.

---

## 0. 세션 시작 시

새 대화 시작 시 `.claude/MEMORY.md`를 읽고 내용을 반영한다. 읽었다는 사실은 따로 말하지 않는다.

---

## 1. 응답 언어

- **기본 언어는 한국어**다. 코드·클래스명·기술 용어는 영어 그대로 사용한다.
- 설명은 자연스러운 구어체로 존댓말 사용, 보고서 형식의 딱딱한 표현은 피한다.

---

## 2. 코딩 규칙

1. **기존 패턴을 최우선으로 따른다.**
   - RestTemplate 기반 외부 API 호출 방식 유지
   - `@Scheduled` 크론식 스케줄러 패턴 유지
   - JPA Repository + Service 계층 구조 유지

2. **간결함을 유지한다.**
   - 불필요한 추상화, 주석, 미래 확장용 코드를 추가하지 않는다.
   - 이미 있는 유틸/패턴을 재사용하고, 새 코드를 최소화한다.
   - 코드 설명을 할 때는 자세히한다.

3. **성능을 고려한다.**
   - JPA N+1 문제를 인식하고, 필요 시 `@EntityGraph` 또는 fetch join을 사용한다.
   - 대량 데이터 수집 시 배치 처리를 고려한다.
   - `@Async` 비동기 처리가 이미 설정되어 있으므로 적극 활용한다.


---

## 3. 작업 방식

- **단순 작업**(단일 파일 수정, 명확한 변경)은 바로 실행한다.
- **복잡한 작업**(여러 파일 수정, 새 기능 추가, 구조 변경)은 계획을 먼저 제시하고 승인 후 진행한다.
- 질문은 꼭 필요한 것만, 최대 3개로 제한한다.
- 불확실한 정보는 **절대 말하지 않는다.** 모르면 "모른다"고 하고, 확인이 필요하면 실제로 확인한 뒤에 말한다.
- 외부 API 응답 필드, 스펙, 동작 방식은 반드시 **실제로 호출하거나 코드를 읽어서 확인**한 뒤에만 언급한다. 학습 데이터 기반 추측을 사실처럼 말하지 않는다.

---

## 4. 프로젝트 컨텍스트

### 기술 스택
- Spring Boot, JPA/Hibernate, MySQL (investdb)
- RestTemplate (외부 API 호출)
- `@Scheduled` + `@Async` (배치/스케줄러)

### 외부 API
| API | 용도 | 키 설정 |
|---|---|---|
| DART | 재무제표, 공시 데이터 | `dart.api.key` |
| 공공데이터포털 | 주가 (XML 파싱) | `stock.api.key` |
| 한국수출입은행 | 환율 | `koreaexim.api.key` |
| KRX | 상장사 정보 | `krx.api.key` |

### 주요 스케줄러 패턴
- 평일 16:00 — 당일 데이터 수집
- 매일 02:00 — 오래된 데이터 정리
- 크론식 예시: `0 0 16 * * MON-FRI`

### 핵심 엔티티
Company, StockPrice, StockIndicator, FinancialStatement, DividendInfo, Exchange, Top100, MarketIndex

---

## 5. 하지 말아야 할 것

- 기존에 없던 의존성을 임의로 추가하지 않는다.
- `ddl-auto=update` 환경이므로 엔티티 변경 시 기존 데이터에 미치는 영향을 반드시 고려한다. 변경이 필요할시 요청 변경을 묻는다.
- API 키를 코드에 하드코딩하지 않는다. 항상 `application.properties` 참조.
- 불확실한 정보를 사실인듯 말하지 않는다.
- 외부 API 응답 필드, 스펙, 동작 방식은 학습 데이터로 추측하지 않는다. 반드시 실제 호출이나 코드를 통해 확인한 뒤에만 언급한다.

## 6. 아래 원칙을 절대 원칙으로 잡는다 

```
# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

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

## 4. Goal-Driven Execution

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
```