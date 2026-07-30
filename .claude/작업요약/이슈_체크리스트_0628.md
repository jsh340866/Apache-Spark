# GitHub 이슈 내용

**제목:** 데이터 수집 파이프라인 개선 및 TOP100 스코어링 시스템 추가

---

## 작업 범위

- 상장폐지 종목 선택 삭제 방식으로 전환 (기존: 전체 삭제 후 재저장)
- 스팩/리츠 필터링 개선
- 재무제표 수집 CFS/OFS 처리 개선
- 다통화(USD/JPY 등) KRW 환산 기능 추가
- 재무지표 계산 개선 (완전자본잠식/적자 기업 null 처리)
- PER/PBR/ROE/부채비율 백분위 정규화 스코어 계산 로직 구현
- TOP100 스코어링 시스템 신규 추가
- 주가/배당 수집 로직 개선

---

## 수정된 파일

**엔티티 / DTO**
- `Company.java` — Cascade 설정 추가
- `DividendInfo.java`, `FinancialStatement.java` — company 연관관계 추가
- `DartItem.java`, `FinancialStatementDto.java` — 필드 변경

**Repository**
- `CompanyRepository.java`, `DividendInfoRepository.java`, `ExchangeRepository.java`
- `FinancialStatementRepository.java`, `StockIndicatorRepository.java`, `Top100Repository.java`

**Service**
- `DartCompanyCollector.java`, `DartFinancialCollector.java`
- `StockPriceCollector.java`, `DividendCollector.java`
- `ExchangeRateApiService.java`, `FinancialIndicatorService.java`, `FinancialStatementService.java`
- `Top100Service.java` *(신규)*

**Scheduler**
- `CompanyScheduler.java`, `FinancialScheduler.java`, `StockPriceScheduler.java`
- `Top100Scheduler.java` *(신규)*

**Controller**
- `CompanyController.java`, `Top100Controller.java` *(신규)*

**설정 / 기타**
- `AsyncConfig.java`, `DemoApplication.java`, `application.properties`
- `CollectorTest.java` *(ControllerTest에서 이름 변경)*

---

## 완료 기준

- [ ] refactor: 상장폐지 종목 선택 삭제 정상 동작
- [ ] feat: 다통화 KRW 환산 정상 동작 (JPY/IDR 포함)
- [ ] fix: PER/PBR/ROE 지표 정상값 확인 (완전자본잠식/적자 기업 null 처리 포함)
- [ ] feat: `Top100Service`, `Top100` 응답 정상 반환
- [ ] feat: 평일 17:00 스코어 자동 계산 동작 확인
- [ ] feat: 매일 02:00 7일 이전 TOP100 데이터 자동 삭제

