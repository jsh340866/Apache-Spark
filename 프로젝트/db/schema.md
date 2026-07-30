# Schema Design Document

> doc/db.md 기반으로 생성된 스키마 설계 근거 문서입니다.
> 엔티티가 변경될 경우 db.md 수정 후 /dbDesign 을 재실행하세요.

---

## MarketIndex (`market_index`)

### 설계 근거
메인 페이지(`/`)에서 코스피·코스닥 지수를 표시하기 위한 테이블. 날짜별 스냅샷으로 저장하며 스케줄러가 영업일 마감 후 1건씩 적재한다.

### 컬럼 상세

| 컬럼명 | 타입 | 제약조건 | 설계 근거 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | 전역 고유 식별자 |
| base_dt | DATE | NOT NULL, UNIQUE | 기준일자. 하루 1건만 존재해야 하므로 UNIQUE |
| kospi_close | DECIMAL(10,2) | NULL | 코스피 종가 지수 |
| kospi_vs | DECIMAL(10,2) | NULL | 코스피 전일 대비 등락 |
| kosdaq_close | DECIMAL(10,2) | NULL | 코스닥 종가 지수 |
| kosdaq_vs | DECIMAL(10,2) | NULL | 코스닥 전일 대비 등락 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 적재일시 |
| updated_at | DATETIME | NOT NULL, DEFAULT NOW() | 수정일시 |

### 관계

| 대상 테이블 | 관계 유형 | 근거 |
|---|---|---|
| (없음) | — | 독립 집계 테이블 |

### 인덱스

| 인덱스명 | 컬럼 | 타입 | 근거 |
|---|---|---|---|
| uq_market_index_base_dt | base_dt | UNIQUE | 날짜별 단건 조회 및 UPSERT 기준 |

---

## Exchange (`exchange`)

### 설계 근거
메인 페이지(`/`)에서 달러-원화 환율을 표시하기 위한 테이블. MarketIndex와 분리하여 환율 데이터를 독립적으로 관리한다. 날짜별 1건 스냅샷으로 적재.

### 컬럼 상세

| 컬럼명 | 타입 | 제약조건 | 설계 근거 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | 전역 고유 식별자 |
| base_dt | DATE | NOT NULL, UNIQUE | 기준일자. 하루 1건 |
| usd_krw_rate | DECIMAL(10,2) | NULL | 달러-원화 환율 |
| usd_krw_vs | DECIMAL(10,2) | NULL | 환율 전일 대비 등락 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 적재일시 |
| updated_at | DATETIME | NOT NULL, DEFAULT NOW() | 수정일시 |

### 관계

| 대상 테이블 | 관계 유형 | 근거 |
|---|---|---|
| (없음) | — | 독립 집계 테이블 |

### 인덱스

| 인덱스명 | 컬럼 | 타입 | 근거 |
|---|---|---|---|
| uq_exchange_base_dt | base_dt | UNIQUE | 날짜별 단건 조회 및 UPSERT 기준 |

---

## Company (`company`)

### 설계 근거
DART list.json API로 수집한 전체 상장 법인 목록. `stock_code`가 KRX 주식시세 API의 `srtnCd`와 동일하여 주가 데이터 매칭의 핵심 키가 된다. `corp_code`는 DART 재무제표 API 호출 기준. 거의 모든 테이블이 이 테이블을 참조하는 허브 테이블이다.

### 컬럼 상세

| 컬럼명 | 타입 | 제약조건 | 설계 근거 |
|---|---|---|---|
| stock_code | VARCHAR(6) | PK | 종목코드 6자리. KRX srtnCd와 동일. 외래키 참조의 기준 |
| corp_code | VARCHAR(8) | UNIQUE, NOT NULL | DART 고유번호. 재무제표·배당 API 호출 시 필수 파라미터 |
| corp_name | VARCHAR(100) | NOT NULL | 종목명. 종목 검색(LIKE), 목록·상세 페이지 표시 |
| corp_cls | CHAR(1) | NULL | 법인구분(Y:유가증권, K:코스닥, N:코넥스, E:기타). 시장 필터링 용도 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 적재일시 |
| updated_at | DATETIME | NOT NULL, DEFAULT NOW() | 수정일시 |

### 관계

| 대상 테이블 | 관계 유형 | 근거 |
|---|---|---|
| financial_statement | 1:N | 한 종목에 여러 연도·보고서 유형의 재무 데이터가 존재 |
| stock_price | 1:1 | db.md ERD 기준 |
| dividend_info | 1:1 | db.md ERD 기준 |
| stock_indicator | 1:1 | db.md ERD 기준 |
| top100 | 1:1 | db.md ERD 기준 |
| user_favorite | 1:N | 여러 유저가 같은 종목을 즐겨찾기 가능 |

### 인덱스

| 인덱스명 | 컬럼 | 타입 | 근거 |
|---|---|---|---|
| (PK) | stock_code | PRIMARY | 모든 외래키 참조의 기준 |
| uq_company_corp_code | corp_code | UNIQUE | DART API 호출 기준, 중복 적재 방지 |
| idx_company_corp_name | corp_name | BTREE | 종목 검색 기능의 LIKE 쿼리 대상 |

---

## FinancialStatement (`financial_statement`)

### 설계 근거
DART fnlttMultiAcnt API 응답을 가공하여 집계 컬럼 형태로 저장하는 테이블. 종목 상세 페이지 하단의 5년 재무추이 차트(매출·영업이익 / ROE / 부채비율)와 연도별 테이블(손익계산서·재무상태표 탭) 데이터 원천.

### 컬럼 상세

| 컬럼명 | 타입 | 제약조건 | 설계 근거 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | 전역 고유 식별자 |
| bsns_year | CHAR(4) | NOT NULL | 사업연도(예: 2024). 5년 추이 조회의 기준 연도 |
| stock_code | VARCHAR(6) | NOT NULL, FK→company | 종목코드. company 테이블과 조인 기준 |
| reprt_code | VARCHAR(5) | NOT NULL | 보고서 코드(11011: 사업보고서). 연간 데이터 기준 필터 |
| fs_div | VARCHAR(5) | NOT NULL | 개별(OFS)/연결(CFS) 구분. 연결 재무제표 우선 표시 |
| period_nm | VARCHAR(50) | NULL | 기간명(예: 제 56 기) |
| period_dt | VARCHAR(50) | NULL | 기간일자 |
| current_assets | BIGINT | NULL | 유동자산 |
| non_current_assets | BIGINT | NULL | 비유동자산 |
| total_assets | BIGINT | NULL | 자산총계. 부채비율 계산 원천 |
| current_liabilities | BIGINT | NULL | 유동부채 |
| non_current_liabilities | BIGINT | NULL | 비유동부채 |
| total_liabilities | BIGINT | NULL | 부채총계. 부채비율 = 부채총계 / 자본총계 |
| capital_stock | BIGINT | NULL | 자본금 |
| retained_earnings | BIGINT | NULL | 이익잉여금 |
| total_equity | BIGINT | NULL | 자본총계. BPS·ROE 계산 원천 |
| revenue | BIGINT | NULL | 매출액. 손익계산서 탭, 매출 추이 차트 |
| operating_income | BIGINT | NULL | 영업이익. 영업이익 추이 차트, 영업이익률 계산 원천 |
| income_before_tax | BIGINT | NULL | 법인세비용차감전순이익 |
| net_income | BIGINT | NULL | 당기순이익. EPS·ROE 계산 원천 |
| currency | VARCHAR(10) | NULL | 통화단위(KRW 등) |

### 관계

| 대상 테이블 | 관계 유형 | 근거 |
|---|---|---|
| company | N:1 | 여러 연도·보고서의 재무 데이터가 하나의 종목에 속함 |

### 인덱스

| 인덱스명 | 컬럼 | 타입 | 근거 |
|---|---|---|---|
| idx_fs_lookup | (stock_code, bsns_year, reprt_code) | BTREE | 종목 상세 페이지 5년 재무 조회의 기본 WHERE 조건 |

---

## StockPrice (`stock_price`)

### 설계 근거
금융위원회 공공데이터 API에서 수집한 일별 주식시세 데이터. 현재가·시가총액·상장주식수는 EPS·BPS·PER·PBR 계산의 입력값이며, 종목 목록·상세 페이지의 현재가 표시에도 사용.

### 컬럼 상세

| 컬럼명 | 타입 | 제약조건 | 설계 근거 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | 전역 고유 식별자 |
| bas_dt | DATE | NOT NULL | 기준일자 |
| srtn_cd | VARCHAR(6) | NOT NULL, FK→company | 단축종목코드. company.stock_code와 동일 |
| isin_cd | VARCHAR(12) | NULL | ISIN 코드 |
| itms_nm | VARCHAR(100) | NULL | 종목명 |
| mrkt_ctg | VARCHAR(10) | NULL | 시장구분(KOSPI/KOSDAQ/KONEX) |
| clpr | BIGINT | NULL | 종가. PER·PBR 계산의 현재가 기준 |
| vs | BIGINT | NULL | 전일 대비 등락 |
| flt_rt | DECIMAL(10,2) | NULL | 등락률 |
| mkp | BIGINT | NULL | 시가 |
| hipr | BIGINT | NULL | 고가 |
| lopr | BIGINT | NULL | 저가 |
| trqu | BIGINT | NULL | 거래량 |
| tr_prc | BIGINT | NULL | 거래대금 |
| lstg_st_cnt | BIGINT | NULL | 상장주식수. EPS = 당기순이익 / lstg_st_cnt |
| mrkt_tot_amt | BIGINT | NULL | 시가총액 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 적재일시 |
| updated_at | DATETIME | NOT NULL, DEFAULT NOW() | 수정일시 |

### 관계

| 대상 테이블 | 관계 유형 | 근거 |
|---|---|---|
| company | 1:1 | db.md ERD 기준 |

### 인덱스

| 인덱스명 | 컬럼 | 타입 | 근거 |
|---|---|---|---|
| idx_stock_price_lookup | (srtn_cd, bas_dt) | BTREE | 종목별 최신 주가 조회 |

---

## DividendInfo (`dividend_info`)

### 설계 근거
배당 정보 수집 테이블. 종목 상세 페이지 배당수익률 표시 및 stock_indicator.dividend_yield 계산 원천.

### 컬럼 상세

| 컬럼명 | 타입 | 제약조건 | 설계 근거 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | 전역 고유 식별자 |
| corp_code | VARCHAR(8) | UNIQUE, NOT NULL | DART 고유번호. company와의 조인 기준 |
| dividend_kind | VARCHAR(6) | NULL | 보통주/우선주 구분 |
| dividend_amount | BIGINT | NULL | 주당배당금 |
| stlm_dt | DATETIME | NULL | 배당일시 |

### 관계

| 대상 테이블 | 관계 유형 | 근거 |
|---|---|---|
| company | 1:1 | db.md ERD 기준 |

### 인덱스

| 인덱스명 | 컬럼 | 타입 | 근거 |
|---|---|---|---|
| uq_dividend_corp_code | corp_code | UNIQUE | 종목별 1건 보장 |

---

## StockIndicator (`stock_indicator`)

### 설계 근거
financial_statement + stock_price 데이터를 기반으로 계산한 파생 지표. 종목 목록 페이지의 PER·PBR·ROE·부채비율 정렬·필터, 상세 페이지의 가치지표 카드 원천.

### 컬럼 상세

| 컬럼명 | 타입 | 제약조건 | 설계 근거 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | 전역 고유 식별자 |
| stock_code | VARCHAR(6) | NOT NULL, FK→company | 종목코드 |
| calc_year | CHAR(4) | NOT NULL | 계산 기준연도 |
| bsns_year | CHAR(4) | NULL | 사용된 재무제표 연도 |
| reprt_code | VARCHAR(10) | NULL | 사용된 보고서 코드 |
| base_dt | DATE | NULL | 사용된 주가 기준일자 |
| eps | DECIMAL(15,2) | NULL | 주당순이익 = 당기순이익 / 상장주식수 |
| bps | DECIMAL(15,2) | NULL | 주당순자산 = 자본총계 / 상장주식수 |
| per | DECIMAL(10,2) | NULL | 주가수익비율 = 현재가 / EPS |
| pbr | DECIMAL(10,4) | NULL | 주가순자산비율 = 현재가 / BPS |
| roe | DECIMAL(10,4) | NULL | 자기자본이익률 = 당기순이익 / 자본총계 × 100 |
| operating_margin | DECIMAL(10,4) | NULL | 영업이익률 = 영업이익 / 매출액 × 100 |
| debt_ratio | DECIMAL(10,4) | NULL | 부채비율 = 부채총계 / 자본총계 × 100 |
| dividend_yield | DECIMAL(10,4) | NULL | 배당수익률 |
| calculated_at | DATETIME | NULL | 지표 계산 시점 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 적재일시 |
| updated_at | DATETIME | NOT NULL, DEFAULT NOW() | 수정일시 |

### 관계

| 대상 테이블 | 관계 유형 | 근거 |
|---|---|---|
| company | 1:1 | db.md ERD 기준 |

### 인덱스

| 인덱스명 | 컬럼 | 타입 | 근거 |
|---|---|---|---|
| uq_indicator_stock_year | (stock_code, calc_year) | UNIQUE | UPSERT 기준 |
| idx_indicator_per | per | BTREE | 종목 목록 PER 정렬·필터 |
| idx_indicator_pbr | pbr | BTREE | 종목 목록 PBR 정렬·필터 |
| idx_indicator_roe | roe | BTREE | 종목 목록 ROE 정렬·필터 |
| idx_indicator_debt | debt_ratio | BTREE | 종목 목록 부채비율 정렬·필터 |

---

## Top100 (`top100`)

### 설계 근거
가치주 종합점수 기반 상위 100 종목의 날짜별 스냅샷. 랭킹 페이지(`/rank`) 전용.

### 컬럼 상세

| 컬럼명 | 타입 | 제약조건 | 설계 근거 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | 전역 고유 식별자 |
| base_dt | DATE | NOT NULL | 기준일자 |
| corp_code | VARCHAR(8) | NOT NULL, FK→company(corp_code) | DART 고유번호 |
| stock_code | VARCHAR(6) | NOT NULL, FK→company | 종목코드 |
| score | INT | NULL | 가치주 종합점수 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 적재일시 |
| updated_at | DATETIME | NOT NULL, DEFAULT NOW() | 수정일시 |

### 관계

| 대상 테이블 | 관계 유형 | 근거 |
|---|---|---|
| company | 1:1 | db.md ERD 기준 |

### 인덱스

| 인덱스명 | 컬럼 | 타입 | 근거 |
|---|---|---|---|
| idx_top100_base_dt | (base_dt, score) | BTREE | 최신 기준일의 점수 순 조회 |

---

## User (`user`)

### 설계 근거
서비스 회원 테이블. 즐겨찾기·투자일지 작성 등 인증이 필요한 기능의 주체. 비밀번호는 BCrypt 해시 저장 필수.

### 컬럼 상세

| 컬럼명 | 타입 | 제약조건 | 설계 근거 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | 전역 고유 식별자 |
| email | VARCHAR(255) | UNIQUE, NOT NULL | 로그인 식별자. 중복 이메일 방지 |
| password | VARCHAR(255) | NOT NULL | BCrypt 해시값 저장. 평문 저장 금지 |
| nickname | VARCHAR(50) | NOT NULL | 투자일지·댓글 작성자 표시명 |
| role | ENUM('USER','ADMIN') | NOT NULL, DEFAULT 'USER' | 권한 레벨 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 가입일시 |
| updated_at | DATETIME | NOT NULL, DEFAULT NOW() | 수정일시 |

### 관계

| 대상 테이블 | 관계 유형 | 근거 |
|---|---|---|
| user_favorite | 1:N | 한 유저가 여러 종목 즐겨찾기 가능 |
| investment_journal | 1:N | 한 유저가 여러 투자일지 작성 가능 |
| comment | 1:N | 한 유저가 여러 댓글 작성 가능 |

### 인덱스

| 인덱스명 | 컬럼 | 타입 | 근거 |
|---|---|---|---|
| uq_user_email | email | UNIQUE | 로그인 조회 및 중복 이메일 방지 |

---

## UserFavorite (`user_favorite`)

### 설계 근거
마이페이지(`/mypage`)의 즐겨찾기 기능. `(user_id, stock_code)` UNIQUE로 동일 종목 중복 즐겨찾기를 방지한다.

### 컬럼 상세

| 컬럼명 | 타입 | 제약조건 | 설계 근거 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | 전역 고유 식별자 |
| user_id | BIGINT | NOT NULL, FK→user | 즐겨찾기를 등록한 유저 |
| stock_code | VARCHAR(6) | NOT NULL, FK→company | 즐겨찾기 대상 종목 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 등록일시 |
| updated_at | DATETIME | NOT NULL, DEFAULT NOW() | 수정일시 |

### 관계

| 대상 테이블 | 관계 유형 | 근거 |
|---|---|---|
| user | N:1 | 한 유저의 즐겨찾기 목록 |
| company | N:1 | 즐겨찾기된 종목 정보 조인 |

### 인덱스

| 인덱스명 | 컬럼 | 타입 | 근거 |
|---|---|---|---|
| uq_favorite_user_stock | (user_id, stock_code) | UNIQUE | 동일 종목 중복 즐겨찾기 방지 |

---

## InvestmentJournal (`investment_journal`)

### 설계 근거
커뮤니티 투자일지 게시글 테이블. `/community` 목록과 `/community/{journalId}` 상세 페이지의 원천.

### 컬럼 상세

| 컬럼명 | 타입 | 제약조건 | 설계 근거 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | 전역 고유 식별자 |
| user_id | BIGINT | NOT NULL, FK→user | 작성자 |
| title | VARCHAR(200) | NOT NULL | 게시글 제목 |
| content | TEXT | NOT NULL | 게시글 본문. HTML Escape 처리 필요 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 작성일시 |
| updated_at | DATETIME | NOT NULL, DEFAULT NOW() | 최종 수정일시 |

### 관계

| 대상 테이블 | 관계 유형 | 근거 |
|---|---|---|
| user | N:1 | 게시글 작성자 |
| comment | 1:N | 게시글에 달린 댓글 목록 |

### 인덱스

| 인덱스명 | 컬럼 | 타입 | 근거 |
|---|---|---|---|
| idx_journal_user_id | user_id | BTREE | 본인 작성 게시글 조회 |

---

## Comment (`comment`)

### 설계 근거
투자일지 댓글 테이블. `/community/{journalId}` 상세 페이지에 표시.

### 컬럼 상세

| 컬럼명 | 타입 | 제약조건 | 설계 근거 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | 전역 고유 식별자 |
| journal_id | BIGINT | NOT NULL, FK→investment_journal | 댓글이 속한 게시글 |
| user_id | BIGINT | NOT NULL, FK→user | 댓글 작성자 |
| content | TEXT | NOT NULL | 댓글 내용. HTML Escape 처리 필요 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 작성일시 |
| updated_at | DATETIME | NOT NULL, DEFAULT NOW() | 수정일시 |

### 관계

| 대상 테이블 | 관계 유형 | 근거 |
|---|---|---|
| investment_journal | N:1 | 댓글이 속한 게시글 |
| user | N:1 | 댓글 작성자 |

### 인덱스

| 인덱스명 | 컬럼 | 타입 | 근거 |
|---|---|---|---|
| idx_comment_journal_id | journal_id | BTREE | 게시글 상세 조회 시 댓글 목록 로드 |

---

## 전체 ERD 요약

```
market_index  (독립)
exchange      (독립)

company ──< financial_statement
company ──  stock_price           (company.stock_code = stock_price.srtn_cd, 1:1)
company ──  dividend_info         (company.corp_code = dividend_info.corp_code, 1:1)
company ──  stock_indicator       (1:1)
company ──  top100                (1:1)
company ──< user_favorite

user    ──< user_favorite >── company
user    ──< investment_journal
user    ──< comment

investment_journal ──< comment
```
