# 금융위원회 주식시세 API 연동 문서

---

## 기본 정보

| 항목 | 내용 |
|---|---|
| API 이름 | 금융위원회 주식시세 API (getStockPriceInfo) |
| 용도 | 현재가·시가총액·상장주식수 수집 |
| 연동 테이블 | `stock_price` |
| 문서 URL | https://www.data.go.kr/data/15094808/openapi.do |

---

## Request

### Endpoint
```
GET https://apis.data.go.kr/1160100/service/GetStockSecuritiesInfoService/getStockPriceInfo
```

### Request Parameters

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| serviceKey | String | Y | 공공데이터포털 인증키 (`krx.api.key`) |
| numOfRows | Integer | Y | 페이지당 결과 수. 최대 (추후 확인 필요) |
| pageNo | Integer | Y | 페이지 번호 |
| resultType | String | N | 응답 형식. json 권장 |
| basDt | String | N | 기준일자 (YYYYMMDD). 미입력 시 최근 영업일 |
| srtnCd | String | N | 단축종목코드. 개별 종목 조회 시 사용 |
| mrktCls | String | N | 시장구분 (KOSPI / KOSDAQ / KONEX) |

---

## Response

### Response Fields

| 필드명 | 타입 | 설명 |
|---|---|---|
| basDt | String | 기준일자 (YYYYMMDD) |
| srtnCd | String | 단축종목코드 |
| isinCd | String | ISIN 코드 |
| itmsNm | String | 종목명 |
| mrktCtg | String | 시장구분 |
| clpr | String | 종가 |
| vs | String | 전일 대비 등락 |
| fltRt | String | 등락률 |
| mkp | String | 시가 |
| hipr | String | 고가 |
| lopr | String | 저가 |
| trqu | String | 거래량 |
| trPrc | String | 거래대금 |
| lstgStCnt | String | 상장주식수 |
| mrktTotAmt | String | 시가총액 |

---

## 저장 매핑

| API 응답 필드 | 저장 테이블 | 저장 컬럼 | 변환/가공 여부 |
|---|---|---|---|
| basDt | stock_price | bas_dt | String → DATE 변환 (YYYYMMDD → DATE) |
| srtnCd | stock_price | srtn_cd | 없음. company.stock_code와 동일 |
| isinCd | stock_price | isin_cd | 없음 |
| itmsNm | stock_price | itms_nm | 없음 |
| mrktCtg | stock_price | mrkt_ctg | 없음 |
| clpr | stock_price | clpr | String → BIGINT 변환 |
| vs | stock_price | vs | String → BIGINT 변환 |
| fltRt | stock_price | flt_rt | String → DECIMAL 변환 |
| mkp | stock_price | mkp | String → BIGINT 변환 |
| hipr | stock_price | hipr | String → BIGINT 변환 |
| lopr | stock_price | lopr | String → BIGINT 변환 |
| trqu | stock_price | trqu | String → BIGINT 변환 |
| trPrc | stock_price | tr_prc | String → BIGINT 변환 |
| lstgStCnt | stock_price | lstg_st_cnt | String → BIGINT 변환 |
| mrktTotAmt | stock_price | mrkt_tot_amt | String → BIGINT 변환 |

---

## 비고

- 인증 방식: Query Parameter `serviceKey`
- 전체 종목 수집 시 페이지네이션 필요 (~2,500건 / 페이지당 최대 건수로 나누어 호출)
- Rate Limit: 공공데이터포털 정책 준수 필요 (추후 확인 필요)
- 수집 주기: 영업일 마감 후 1회 (장 마감 ~18:00 이후)
- `srtnCd`가 company.stock_code에 없는 종목은 stock_price만 적재하고 지표 계산에서 제외
- 캐싱 정책: 당일 이미 적재된 bas_dt 레코드는 UPSERT로 처리
