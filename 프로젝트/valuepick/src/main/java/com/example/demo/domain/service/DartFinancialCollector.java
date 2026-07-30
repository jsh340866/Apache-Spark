package com.example.demo.domain.service;

import com.example.demo.domain.dart.DartItem;
import com.example.demo.domain.dart.DartResponse;
import com.example.demo.domain.dto.FinancialStatementDto;
import com.example.demo.domain.entity.Company;
import com.example.demo.domain.repository.CompanyRepository;
import com.example.demo.domain.repository.FinancialStatementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class DartFinancialCollector {

    private final RestTemplate restTemplate;
    private final CompanyRepository companyRepository;
    private final FinancialStatementRepository financialStatementRepository;

    @Value("${dart.api.key}")
    private String apiKey;

    private static final int SLEEP_MS = 100; // DART API 호출 간격 (차단 방지)
    private static final int RETRY_COUNT = 3; // 실패 시 재시도 횟수

    private static final String FS_DIV_CFS = "CFS"; // 연결재무제표
    private static final String FS_DIV_OFS = "OFS"; // 별도재무제표

    // IFRS/DART 표준 계정 코드(account_id) - 회사마다 계정명(account_nm) 표기가 달라도 항상 동일
    // (예: 매출액="매출액"/"영업수익"/"수익(매출액)", 영업이익="영업이익"/"영업이익(손실)" 등 표기는 제각각이지만 코드는 고정)
    // 매출액·영업이익은 업종에 따라 쓰는 표준 코드 자체가 다름(증권·보험사는 IFRS 표준 코드, 제조업은 DART 자체 코드) - 후보 여러 개 중 매칭
    private static final List<String> ACC_REVENUE = List.of("ifrs-full_Revenue", "ifrs-full_InsuranceRevenue");
    private static final List<String> ACC_OPERATING_INCOME = List.of("dart_OperatingIncomeLoss", "ifrs-full_ProfitLossFromOperatingActivities");
    private static final String ACC_NET_INCOME = "ifrs-full_ProfitLoss";
    private static final String ACC_TOTAL_ASSETS = "ifrs-full_Assets";
    private static final String ACC_TOTAL_LIABILITIES = "ifrs-full_Liabilities";
    private static final String ACC_TOTAL_EQUITY = "ifrs-full_Equity";
    private static final String ACC_CURRENT_ASSETS = "ifrs-full_CurrentAssets";
    private static final String ACC_CURRENT_LIABILITIES = "ifrs-full_CurrentLiabilities";
    private static final String ACC_OPERATING_CASH_FLOW = "ifrs-full_CashFlowsFromUsedInOperatingActivities";
    private static final String ACC_GROSS_PROFIT = "ifrs-full_GrossProfit";

    // account_id가 "-표준계정코드 미사용-"이거나 아예 없는 회사(DART 자체 태깅 누락)를 위한 2순위 안전망
    // account_id로 못 찾을 때만 계정명(account_nm) 텍스트로 재시도
    private static final List<String> NM_REVENUE = List.of("매출액", "영업수익", "영업수익(손실)", "수익(매출액)");
    private static final List<String> NM_OPERATING_INCOME = List.of("영업이익", "영업이익(손실)");
    private static final List<String> NM_NET_INCOME = List.of("당기순이익(손실)", "당기순이익", "당기순손익");
    private static final List<String> NM_TOTAL_ASSETS = List.of("자산총계");
    private static final List<String> NM_TOTAL_LIABILITIES = List.of("부채총계");
    private static final List<String> NM_TOTAL_EQUITY = List.of("자본총계");
    private static final List<String> NM_CURRENT_ASSETS = List.of("유동자산");
    private static final List<String> NM_CURRENT_LIABILITIES = List.of("유동부채");
    private static final List<String> NM_OPERATING_CASH_FLOW = List.of("영업활동현금흐름", "영업활동으로 인한 현금흐름");
    private static final List<String> NM_GROSS_PROFIT = List.of("매출총이익", "매출총이익(손실)");

    // 표준 코드 자체를 안 쓴 회사 항목("-표준계정코드 미사용-")을 걸러내기 위한 표시 문자열
    private static final String NO_STANDARD_ACCOUNT_ID = "-표준계정코드 미사용-";

    // 3개년(당기·전기·전전기) 수집 — 초기 적재 시 사용
    @Async("dartExecutor")
    public void collect(String year, String reprtCode) {
        doCollect(year, reprtCode, 3);
    }

    // 1개년(당기만) 수집 — 연간 업데이트 시 사용 (API 1번 호출, 당기 데이터만 저장)
    @Async("dartExecutor")
    public void collectCurrentOnly(String year, String reprtCode) {
        doCollect(year, reprtCode, 1);
    }

    private void doCollect(String year, String reprtCode, int yearsToCollect) {

        int savedCount = 0;
        int page = 0;
        final int PAGE_SIZE = 100;

        while (true) {

            Pageable pageable = PageRequest.of(page, PAGE_SIZE);
            Page<Company> companyPage = companyRepository.findAll(pageable);
            List<Company> companies = companyPage.getContent();

            if (companies.isEmpty()) break;

            for (Company company : companies) {

                try {

                    // CFS(연결재무제표) 먼저 시도, 자회사 없어 연결재무제표 자체가 없는 회사는 OFS(별도재무제표)로 재시도
                    DartResponse response = requestWithRetry(company.getCorpCode(), year, reprtCode, FS_DIV_CFS);
                    String finalFsDiv = FS_DIV_CFS;

                    if (!isValidResponse(response)) {
                        Thread.sleep(SLEEP_MS);
                        response = requestWithRetry(company.getCorpCode(), year, reprtCode, FS_DIV_OFS);
                        finalFsDiv = FS_DIV_OFS;
                    }

                    if (!isValidResponse(response)) {
                        log.warn("재무데이터 없음(CFS/OFS 모두): {}", company.getCorpName());
                        continue;
                    }

                    List<FinancialStatementDto> dtos = mapToDtos(company, response.getList(), year, reprtCode, yearsToCollect, finalFsDiv);
                    for (FinancialStatementDto dto : dtos) {
                        if (financialStatementRepository.findByStockCodeAndYearAndReprtCode(
                                company.getStockCode(), dto.getBsnsYear(), reprtCode).isPresent()) {
                            log.info("이미 존재, 스킵: {} ({}년)", company.getCorpName(), dto.getBsnsYear());
                            continue;
                        }
                        financialStatementRepository.save(dto.toEntity(company));
                        savedCount++;
                    }
                    log.info("저장 완료: {} (page={}, total={})", company.getCorpName(), page, savedCount);

                    Thread.sleep(SLEEP_MS);

                } catch (Exception e) {
                    log.error("처리 실패: {}", company.getCorpName(), e);
                }
            }

            if (!companyPage.hasNext()) break;
            page++;
        }

        log.info("FinancialStatement 저장 완료: {}건 ({}개년)", savedCount, yearsToCollect);
    }

    // DART API 호출 (재시도 포함)
    private DartResponse requestWithRetry(String corpCode, String year, String reprtCode, String fsDiv) {

        String url = buildUrl(corpCode, year, reprtCode, fsDiv);

        for (int i = 0; i < RETRY_COUNT; i++) {

            try {
                ResponseEntity<DartResponse> response =
                        restTemplate.getForEntity(url, DartResponse.class);
                return response.getBody();

            } catch (Exception e) {
                log.warn("재시도 {}/{}", i + 1, RETRY_COUNT);
                try {
                    Thread.sleep(500L * (i + 1)); // 재시도마다 대기시간 증가
                } catch (InterruptedException ignored) {}
            }
        }
        return null;
    }

    // 응답에 실제 데이터가 있는지 확인 (fs_div에 해당하는 재무제표 자체가 없으면 status="013")
    private boolean isValidResponse(DartResponse response) {
        return response != null && "000".equals(response.getStatus())
                && response.getList() != null && !response.getList().isEmpty();
    }

    // DART 단일회사 전체 재무제표 API URL 생성 (BS/CIS/IS/CF 전 항목 포함, fs_div 필수 지정)
    private String buildUrl(String corpCode, String year, String reprtCode, String fsDiv) {
        return "https://opendart.fss.or.kr/api/fnlttSinglAcntAll.json"
                + "?crtfc_key=" + apiKey
                + "&corp_code=" + corpCode
                + "&bsns_year=" + year
                + "&reprt_code=" + reprtCode
                + "&fs_div=" + fsDiv;
    }

    // API 응답 리스트 → DTO 리스트 변환 (yearsToCollect: 1=당기만, 3=당기·전기·전전기)
    // account_id(IFRS/DART 표준 코드) 기준으로 1차 매칭하고, 회사가 표준 코드를 안 쓴 경우를 대비해
    // account_nm(계정명) 기준 맵도 같이 구성해서 2순위 안전망으로 사용
    private List<FinancialStatementDto> mapToDtos(Company company, List<DartItem> items,
                                                   String year, String reprtCode, int yearsToCollect, String fsDiv) {
        Map<String, Long> currById = new HashMap<>(), prevById = new HashMap<>(), prevPrevById = new HashMap<>();
        Map<String, Long> currByNm = new HashMap<>(), prevByNm = new HashMap<>(), prevPrevByNm = new HashMap<>();
        String currency = "KRW";

        for (DartItem item : items) {
            String id = item.getAccountId();
            String name = item.getAccountNm();

            if (id != null && !id.isBlank() && !NO_STANDARD_ACCOUNT_ID.equals(id)) {
                if (!currById.containsKey(id))     currById.put(id,     parseAmount(item.getAmount()));
                if (!prevById.containsKey(id))      prevById.put(id,     parseAmount(item.getFrmtrm_amount()));
                if (!prevPrevById.containsKey(id))  prevPrevById.put(id, parseAmount(item.getBfefrmtrm_amount()));
            }
            if (name != null && !name.isBlank()) {
                if (!currByNm.containsKey(name))     currByNm.put(name,     parseAmount(item.getAmount()));
                if (!prevByNm.containsKey(name))      prevByNm.put(name,     parseAmount(item.getFrmtrm_amount()));
                if (!prevPrevByNm.containsKey(name))  prevPrevByNm.put(name, parseAmount(item.getBfefrmtrm_amount()));
            }
            if (item.getCurrency() != null && !item.getCurrency().isBlank()) currency = item.getCurrency();
        }

        int y = Integer.parseInt(year);
        List<FinancialStatementDto> all = List.of(
            toDto(company, currById,     currByNm,     currency, String.valueOf(y),     reprtCode, fsDiv),
            toDto(company, prevById,     prevByNm,     currency, String.valueOf(y - 1), reprtCode, fsDiv),
            toDto(company, prevPrevById, prevPrevByNm, currency, String.valueOf(y - 2), reprtCode, fsDiv)
        );
        return all.subList(0, Math.min(yearsToCollect, 3));
    }

    private FinancialStatementDto toDto(Company company, Map<String, Long> byId, Map<String, Long> byNm,
                                         String currency, String bsnsYear, String reprtCode, String fsDiv) {
        return FinancialStatementDto.builder()
                .bsnsYear(bsnsYear)
                .stockCode(company.getStockCode())
                .reprtCode(reprtCode)
                .fsDiv(fsDiv)
                .currency(currency)
                .revenue(pick(byId, ACC_REVENUE, byNm, NM_REVENUE))
                .operatingIncome(pick(byId, ACC_OPERATING_INCOME, byNm, NM_OPERATING_INCOME))
                .netIncome(pick(byId, List.of(ACC_NET_INCOME), byNm, NM_NET_INCOME))
                .totalAssets(pick(byId, List.of(ACC_TOTAL_ASSETS), byNm, NM_TOTAL_ASSETS))
                .totalLiabilities(pick(byId, List.of(ACC_TOTAL_LIABILITIES), byNm, NM_TOTAL_LIABILITIES))
                .totalEquity(pick(byId, List.of(ACC_TOTAL_EQUITY), byNm, NM_TOTAL_EQUITY))
                .currentAssets(pick(byId, List.of(ACC_CURRENT_ASSETS), byNm, NM_CURRENT_ASSETS))
                .currentLiabilities(pick(byId, List.of(ACC_CURRENT_LIABILITIES), byNm, NM_CURRENT_LIABILITIES))
                .operatingCashFlow(pick(byId, List.of(ACC_OPERATING_CASH_FLOW), byNm, NM_OPERATING_CASH_FLOW))
                .grossProfit(pick(byId, List.of(ACC_GROSS_PROFIT), byNm, NM_GROSS_PROFIT))
                .build();
    }

    // account_id 후보 먼저 시도, 못 찾으면 account_nm 후보로 재시도 (표준 코드 자체를 안 쓴 회사 대비)
    private Long pick(Map<String, Long> byId, List<String> ids, Map<String, Long> byNm, List<String> names) {
        for (String id : ids) {
            if (byId.containsKey(id)) return byId.get(id);
        }
        for (String name : names) {
            if (byNm.containsKey(name)) return byNm.get(name);
        }
        return 0L;
    }

    // 금액 문자열 → Long 변환 (null, 공백, "-" 처리)
    // 응답에는 재무제표 금액(정수) 외에 EPS 등 소수점 포함 항목도 섞여 있어서(예: "69.37") BigDecimal로 파싱 후 정수부만 사용
    private Long parseAmount(String amount) {
        if (amount == null || amount.isBlank() || "-".equals(amount.trim())) return 0L;
        return new BigDecimal(amount.replace(",", "").trim()).longValue();
    }
}
