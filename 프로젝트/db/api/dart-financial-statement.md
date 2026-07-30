# DART OpenAPI (재무제표) 연동 문서

---

## 기본 정보

| 항목 | 내용 |
|---|---|
| API 이름 | DART OpenAPI — 다중회사 주요계정 (fnlttMultiAcnt) |
| 용도 | 재무제표 원문 수집 → financial_statement 집계 저장 |
| 연동 테이블 | `financial_statement` |
| 문서 URL | https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS003&apiId=2019017 |

---

## Request

### Endpoint
```
GET https://opendart.fss.or.kr/api/fnlttMultiAcnt.json
```

### Request Parameters

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| crtfc_key | String | Y | DART API 인증키 (`dart.api.key`) |
| corp_code | String | Y | DART 고유번호. 최대 100개 콤마 구분 |
| bsns_year | String(4) | Y | 사업연도 (예: 2024) |
| reprt_code | String(5) | Y | 보고서 코드. 11011: 사업보고서 |

---

## Response

### Response Fields

| 필드명 | 타입 | 설명 |
|---|---|---|
| rcept_no | String | 접수번호 |
| reprt_code | String | 보고서 코드 |
| bsns_year | String | 사업연도 |
| corp_code | String | 고유번호 |
| sj_div | String | 재무제표 구분 (BS: 재무상태표, IS: 손익계산서) |
| sj_nm | String | 재무제표명 |
| account_id | String | 계정 ID |
| account_nm | String | 계정명 |
| account_detail | String | 계정상세 |
| thstrm_nm | String | 당기명 |
| thstrm_amount | String | 당기금액 |
| frmtrm_nm | String | 전기명 |
| frmtrm_amount | String | 전기금액 |
| bfefrmtrm_nm | String | 전전기명 |
| bfefrmtrm_amount | String | 전전기금액 |
| ord | String | 정렬순서 |
| currency | String | 통화단위 |

---

## 저장 매핑

| API 응답 필드 | 저장 테이블 | 저장 컬럼 | 변환/가공 여부 |
|---|---|---|---|
| bsns_year | financial_statement | bsns_year | 없음 |
| corp_code → stock_code | financial_statement | stock_code | company 테이블에서 corp_code로 stock_code 조회 |
| reprt_code | financial_statement | reprt_code | 없음 |
| — | financial_statement | fs_div | (추후 확인 필요 — API 응답에서 OFS/CFS 구분 필드 확인) |
| thstrm_nm | financial_statement | period_nm | 없음 |
| — | financial_statement | period_dt | (추후 확인 필요) |
| account_nm=유동자산, thstrm_amount | financial_statement | current_assets | account_nm 값으로 필터링 후 집계 |
| account_nm=비유동자산, thstrm_amount | financial_statement | non_current_assets | account_nm 값으로 필터링 후 집계 |
| account_nm=자산총계, thstrm_amount | financial_statement | total_assets | account_nm 값으로 필터링 후 집계 |
| account_nm=유동부채, thstrm_amount | financial_statement | current_liabilities | account_nm 값으로 필터링 후 집계 |
| account_nm=비유동부채, thstrm_amount | financial_statement | non_current_liabilities | account_nm 값으로 필터링 후 집계 |
| account_nm=부채총계, thstrm_amount | financial_statement | total_liabilities | account_nm 값으로 필터링 후 집계 |
| account_nm=자본금, thstrm_amount | financial_statement | capital_stock | account_nm 값으로 필터링 후 집계 |
| account_nm=이익잉여금, thstrm_amount | financial_statement | retained_earnings | account_nm 값으로 필터링 후 집계 |
| account_nm=자본총계, thstrm_amount | financial_statement | total_equity | account_nm 값으로 필터링 후 집계 |
| account_nm=매출액, thstrm_amount | financial_statement | revenue | account_nm 값으로 필터링 후 집계 |
| account_nm=영업이익, thstrm_amount | financial_statement | operating_income | account_nm 값으로 필터링 후 집계 |
| account_nm=법인세비용차감전순이익, thstrm_amount | financial_statement | income_before_tax | account_nm 값으로 필터링 후 집계 |
| account_nm=당기순이익, thstrm_amount | financial_statement | net_income | account_nm 값으로 필터링 후 집계 |
| currency | financial_statement | currency | 없음 |

---

## 비고

- 인증 방식: Query Parameter `crtfc_key`
- corp_code 최대 100개 배치 호출 → 전체 2,500개사 기준 최소 25회 API 호출
- Rate Limit: DART 정책 준수 필요 (추후 확인 필요). 호출 간 딜레이 권장
- account_nm 매핑 기준: 동일 계정명이 연결/개별 구분별로 중복 등장할 수 있음. `fs_div` 기준으로 연결(CFS) 우선 선택
- 캐싱 정책: 연 1회 사업보고서 기준 적재. 기적재 연도는 스킵
