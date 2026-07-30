# DART OpenAPI (공시검색) 연동 문서

---

## 기본 정보

| 항목 | 내용 |
|---|---|
| API 이름 | DART OpenAPI — 공시검색 (list.json) |
| 용도 | 공시 목록에서 corp_code·stock_code·corp_name·corp_cls 수집 → company 테이블 적재 |
| 연동 테이블 | `company` |
| 문서 URL | https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001 |

---

## Request

### Endpoint
```
GET https://opendart.fss.or.kr/api/list.json
```

### Request Parameters

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| crtfc_key | STRING(40) | Y | DART API 인증키 (`dart.api.key`) |
| corp_code | STRING(8) | N | 공시대상회사 고유번호. 미입력 시 전체 조회 |
| bgn_de | STRING(8) | N | 검색시작일 (YYYYMMDD) |
| end_de | STRING(8) | N | 검색종료일 (YYYYMMDD) |
| last_reprt_at | STRING(1) | N | 최종보고서만 검색 (Y/N) |
| pblntf_ty | STRING(1) | N | 공시유형 (A~J) |
| pblntf_detail_ty | STRING(4) | N | 공시상세유형 |
| corp_cls | STRING(1) | N | 법인구분 필터 (Y:유가증권 / K:코스닥 / N:코넥스 / E:기타) |
| sort | STRING(4) | N | 정렬기준 (date/crp/rpt) |
| sort_mth | STRING(4) | N | 정렬방법 (asc/desc) |
| page_no | STRING(5) | N | 페이지 번호 (기본값: 1) |
| page_count | STRING(3) | N | 페이지당 건수 (1~100) |

---

## Response

### 상위 필드

| 필드명 | 타입 | 설명 |
|---|---|---|
| status | String | 응답코드 (000: 정상) |
| message | String | 응답메시지 |
| page_no | Integer | 현재 페이지 번호 |
| page_count | Integer | 페이지당 건수 |
| total_count | Integer | 총 건수 |
| total_page | Integer | 총 페이지 수 |
| list | Array | 공시 목록 |

### list 배열 내 필드

| 필드명 | 타입 | 설명 |
|---|---|---|
| corp_cls | String | 법인구분 (Y/K/N/E) |
| corp_name | String | 공시대상 회사명 |
| corp_code | String(8) | DART 고유번호 |
| stock_code | String(6) | 종목코드 (비상장사는 공백) |
| report_nm | String | 보고서명 |
| rcept_no | String | 접수번호 |
| flr_nm | String | 공시 제출인명 |
| rcept_dt | String | 접수일자 (YYYYMMDD) |
| rm | String | 비고 |

---

## 저장 매핑

| API 응답 필드 | 저장 테이블 | 저장 컬럼 | 변환/가공 여부 |
|---|---|---|---|
| corp_code | company | corp_code | 없음 |
| corp_name | company | corp_name | 없음 |
| stock_code | company | stock_code | 공백이면 저장 제외 (비상장사 필터) |
| corp_cls | company | corp_cls | 없음 |

---

## 비고

- 인증 방식: Query Parameter `crtfc_key`
- 응답 형식: JSON
- 페이지네이션 필수: `total_page` 기준으로 전체 페이지 순회
- `stock_code`가 공백인 비상장 법인은 company 테이블에 저장하지 않음
- Rate Limit: (추후 확인 필요)
- 캐싱 정책: (추후 결정)
