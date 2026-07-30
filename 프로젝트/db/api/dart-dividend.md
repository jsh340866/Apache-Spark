# DART OpenAPI (배당정보) 연동 문서

---

## 기본 정보

| 항목 | 내용 |
|---|---|
| API 이름 | DART OpenAPI — 배당에 관한 사항 (alotMatter) |
| 용도 | 종목별 주당배당금 수집 → 배당수익률 계산 원천 |
| 연동 테이블 | `dividend_info` |
| 문서 URL | https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS002&apiId=2019005 |

---

## Request

### Endpoint
```
GET https://opendart.fss.or.kr/api/alotMatter.json
```

### Request Parameters

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| crtfc_key | String | Y | DART API 인증키 (`dart.api.key`) |
| corp_code | String(8) | Y | DART 고유번호 |
| bsns_year | String(4) | Y | 사업연도 (예: 2024) |
| reprt_code | String(5) | Y | 보고서 코드. 11011: 사업보고서 |

---

## Response

### Response Fields

| 필드명 | 타입 | 설명 |
|---|---|---|
| rcept_no | String | 접수번호 |
| corp_cls | String | 법인구분 |
| corp_code | String | 고유번호 |
| corp_name | String | 법인명 |
| se | String | 구분 (보통주 / 우선주 등) |
| stock_knd | String | 주식 종류 |
| thstrm | String | 당기 주당배당금 (원) |
| frmtrm | String | 전기 주당배당금 |
| lwfr | String | 전전기 주당배당금 |

---

## 저장 매핑

| API 응답 필드 | 저장 테이블 | 저장 컬럼 | 변환/가공 여부 |
|---|---|---|---|
| corp_code | dividend_info | corp_code | 없음 |
| se | dividend_info | dividend_kind | 보통주/우선주 구분값 그대로 저장 |
| thstrm | dividend_info | dividend_amount | String → BIGINT 변환. 쉼표 제거 후 파싱 |
| — | dividend_info | stlm_dt | 보고서 기준일 또는 배당기준일 (추후 확인 필요) |

---

## 비고

- 인증 방식: Query Parameter `crtfc_key`
- corp_code 단건 호출. 전체 2,500개사 기준 최소 2,500회 API 호출
- Rate Limit: DART 정책 준수 필요 (추후 확인 필요). 호출 간 딜레이 적용 권장
- `se` 필드 값이 "보통주"인 레코드 기준으로 `dividend_amount` 저장
- `thstrm` 값이 없거나 "-"인 경우 배당 없음으로 처리 (0 또는 NULL 저장)
- 수집 주기: 연 1회 사업보고서 공시 후 적재
- 캐싱 정책: 동일 corp_code + 사업연도 기적재 시 UPSERT 처리
