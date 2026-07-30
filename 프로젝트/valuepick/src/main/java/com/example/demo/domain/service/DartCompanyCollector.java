package com.example.demo.domain.service;

import com.example.demo.domain.entity.Company;
import com.example.demo.domain.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class DartCompanyCollector {

    private final CompanyRepository companyRepository;
    private final RestTemplate restTemplate;

    @Value("${dart.api.key}")
    private String dartApiKey;

    @Value("${stock.api.key}")
    private String stockApiKey;

    private static final String KRX_LISTED_URL = "https://apis.data.go.kr/1160100/service/GetKrxListedInfoService/getItemInfo";

    // KSIC(한국표준산업분류) 10차 개정 중분류(2자리) 코드→업종명 매핑
    // induty_code는 회사마다 3~5자리로 등록 깊이가 달라서, 항상 동일한 레벨인 앞 2자리(중분류)만 잘라서 사용
    private static final Map<String, String> INDUTY_NAME_MAP = Map.ofEntries(
            Map.entry("01", "농업"),
            Map.entry("02", "임업"),
            Map.entry("03", "어업"),
            Map.entry("05", "석탄, 원유 및 천연가스 광업"),
            Map.entry("06", "금속 광업"),
            Map.entry("07", "비금속광물 광업; 연료용 제외"),
            Map.entry("08", "광업 지원 서비스업"),
            Map.entry("10", "식료품 제조업"),
            Map.entry("11", "음료 제조업"),
            Map.entry("12", "담배 제조업"),
            Map.entry("13", "섬유제품 제조업; 의복 제외"),
            Map.entry("14", "의복, 의복 액세서리 및 모피제품 제조업"),
            Map.entry("15", "가죽, 가방 및 신발 제조업"),
            Map.entry("16", "목재 및 나무제품 제조업; 가구 제외"),
            Map.entry("17", "펄프, 종이 및 종이제품 제조업"),
            Map.entry("18", "인쇄 및 기록매체 복제업"),
            Map.entry("19", "코크스, 연탄 및 석유정제품 제조업"),
            Map.entry("20", "화학 물질 및 화학제품 제조업; 의약품 제외"),
            Map.entry("21", "의료용 물질 및 의약품 제조업"),
            Map.entry("22", "고무 및 플라스틱제품 제조업"),
            Map.entry("23", "비금속 광물제품 제조업"),
            Map.entry("24", "1차 금속 제조업"),
            Map.entry("25", "금속 가공제품 제조업; 기계 및 가구 제외"),
            Map.entry("26", "전자 부품, 컴퓨터, 영상, 음향 및 통신장비 제조업"),
            Map.entry("27", "의료, 정밀, 광학 기기 및 시계 제조업"),
            Map.entry("28", "전기장비 제조업"),
            Map.entry("29", "기타 기계 및 장비 제조업"),
            Map.entry("30", "자동차 및 트레일러 제조업"),
            Map.entry("31", "기타 운송장비 제조업"),
            Map.entry("32", "가구 제조업"),
            Map.entry("33", "기타 제품 제조업"),
            Map.entry("34", "산업용 기계 및 장비 수리업"),
            Map.entry("35", "전기, 가스, 증기 및 공기 조절 공급업"),
            Map.entry("36", "수도업"),
            Map.entry("37", "하수, 폐수 및 분뇨 처리업"),
            Map.entry("38", "폐기물 수집, 운반, 처리 및 원료 재생업"),
            Map.entry("39", "환경 정화 및 복원업"),
            Map.entry("41", "종합 건설업"),
            Map.entry("42", "전문직별 공사업"),
            Map.entry("45", "자동차 및 부품 판매업"),
            Map.entry("46", "도매 및 상품 중개업"),
            Map.entry("47", "소매업; 자동차 제외"),
            Map.entry("49", "육상 운송 및 파이프라인 운송업"),
            Map.entry("50", "수상 운송업"),
            Map.entry("51", "항공 운송업"),
            Map.entry("52", "창고 및 운송관련 서비스업"),
            Map.entry("55", "숙박업"),
            Map.entry("56", "음식점 및 주점업"),
            Map.entry("58", "출판업"),
            Map.entry("59", "영상ㆍ오디오 기록물 제작 및 배급업"),
            Map.entry("60", "방송업"),
            Map.entry("61", "우편 및 통신업"),
            Map.entry("62", "컴퓨터 프로그래밍, 시스템 통합 및 관리업"),
            Map.entry("63", "정보서비스업"),
            Map.entry("64", "금융업"),
            Map.entry("65", "보험 및 연금업"),
            Map.entry("66", "금융 및 보험관련 서비스업"),
            Map.entry("68", "부동산업"),
            Map.entry("70", "연구개발업"),
            Map.entry("71", "전문 서비스업"),
            Map.entry("72", "건축 기술, 엔지니어링 및 기타 과학기술 서비스업"),
            Map.entry("73", "기타 전문, 과학 및 기술 서비스업"),
            Map.entry("74", "사업시설 관리 및 조경 서비스업"),
            Map.entry("75", "사업 지원 서비스업"),
            Map.entry("76", "임대업; 부동산 제외"),
            Map.entry("84", "공공 행정, 국방 및 사회보장 행정"),
            Map.entry("85", "교육 서비스업"),
            Map.entry("86", "보건업"),
            Map.entry("87", "사회복지 서비스업"),
            Map.entry("90", "창작, 예술 및 여가관련 서비스업"),
            Map.entry("91", "스포츠 및 오락관련 서비스업"),
            Map.entry("94", "협회 및 단체"),
            Map.entry("95", "개인 및 소비용품 수리업"),
            Map.entry("96", "기타 개인 서비스업"),
            Map.entry("97", "가구 내 고용활동"),
            Map.entry("98", "달리 분류되지 않은 자가 소비를 위한 가구의 재화 및 서비스 생산활동"),
            Map.entry("99", "국제 및 외국기관")
    );


    @Async("dartExecutor")
    public void collectCompanies(String basDt) {

        try {

            // 1단계: KRX 상장종목 정보 수집 (KOSPI + KOSDAQ)
            Map<String, KrxStockInfo> krxStockMap = collectKrxStockInfo(basDt);
            log.info("KRX 상장 종목 수집 완료: {}건", krxStockMap.size());

            // 2단계: DART corpCode.xml에서 KRX 종목과 매핑되는 stockCode→corpCode 맵 추출
            Map<String, String> stockToCorpMap = collectCorpCodeMap(krxStockMap.keySet());
            log.info("DART corpCode 매핑 완료: {}건", stockToCorpMap.size());

            // 3단계: DB에 있지만 KRX 목록에 없는 종목 = 상장폐지 → cascade 삭제
            Set<String> currentListedCodes = krxStockMap.keySet();
            List<String> delistedCodes = companyRepository.findAllIds().stream()
                    .filter(code -> !currentListedCodes.contains(code))
                    .toList();

            if (!delistedCodes.isEmpty()) {
                companyRepository.deleteAllById(delistedCodes);
                log.info("상장폐지 종목 삭제: {}건", delistedCodes.size());
            }

            int savedCount = 0;

            // 4단계: KRX 정보 + corpCode 합쳐서 Company 저장
            for (Map.Entry<String, String> entry : stockToCorpMap.entrySet()) {

                String stockCode = entry.getKey();
                String corpCode = entry.getValue();
                KrxStockInfo krxInfo = krxStockMap.get(stockCode);

                if (krxInfo == null) continue;

                try {
                    Company company = Company.builder()
                            .stockCode(stockCode)
                            .corpCode(corpCode)
                            .corpName(krxInfo.corpName())
                            .corpCls(krxInfo.corpCls())
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();

                    companyRepository.save(company);
                    savedCount++;
                    log.info("기업정보 저장 완료: {} {} {} (total={})", krxInfo.corpName(), stockCode, krxInfo.corpCls(), savedCount);

                } catch (Exception e) {
                    log.error("기업 처리 실패: {}", stockCode, e);
                }
            }

            log.info("전체 기업정보 저장 완료: {}건", savedCount);

            // 5단계: 저장된 Company의 corpCode로 DART company.json 호출 → 업종코드, 대표자명 저장
            collectIndustryInfo();

        } catch (Exception e) {
            log.error("기업정보 수집 실패", e);
            throw new RuntimeException(e);
        }
    }

    // 전 종목 순회하며 DART company.json 호출 - induty_code, ceo_nm 업데이트 (호출 간격 sleep 적용)
    private void collectIndustryInfo() {

        int page = 0;
        int updatedCount = 0;
        final int PAGE_SIZE = 100; // StockPriceCollector와 동일하게 100건씩 페이징 처리

        while (true) {

            // DB에 이미 저장된 Company를 페이징으로 조회 (findAll(pageable)로 가져온 엔티티는 영속 상태 = managed)
            Pageable pageable = PageRequest.of(page, PAGE_SIZE);
            Page<Company> companyPage = companyRepository.findAll(pageable);
            List<Company> companies = companyPage.getContent();

            if (companies.isEmpty()) break; // 더 조회할 데이터 없으면 종료

            for (Company company : companies) {
                try {
                    // corpCode로 DART 기업개황 API 호출 (응답 필드: induty_code, ceo_nm 등)
                    Map<String, Object> response = restTemplate.getForObject(buildCompanyInfoUrl(company.getCorpCode()), Map.class);

                    // 응답 없거나 status가 정상(000)이 아니면 스킵
                    if (response == null || !"000".equals(response.get("status"))) {
                        log.warn("업종정보 조회 실패: {}", company.getCorpName());
                        continue;
                    }

                    String indutyCode = (String) response.get("induty_code"); // 표준산업분류코드 (예: "264")
                    String indutyNm = toIndutyNm(indutyCode); // 앞 2자리(중분류)로 업종명 조회

                    // managed 엔티티를 setter로 직접 수정 → save() 시 merge 없이 dirty checking으로 UPDATE만 발생
                    company.setIndustryInfo(
                            indutyCode,
                            indutyNm,
                            (String) response.get("ceo_nm") // 대표자명 (복수 대표는 콤마로 구분된 문자열)
                    );
                    companyRepository.save(company);
                    updatedCount++;

                    Thread.sleep(100); // DART API 호출 간격 유지 (차단 방지) - DartFinancialCollector와 동일 패턴

                } catch (Exception e) {
                    log.error("업종정보 처리 실패: {}", company.getCorpName(), e);
                }
            }

            if (!companyPage.hasNext()) break; // 마지막 페이지면 종료
            page++;
        }

        log.info("업종정보 업데이트 완료: {}건", updatedCount);
    }

    // DART 기업개황 API URL 생성 - corpCode로 induty_code, ceo_nm 등 회사 상세정보 조회
    private String buildCompanyInfoUrl(String corpCode) {
        return "https://opendart.fss.or.kr/api/company.json?crtfc_key=" + dartApiKey + "&corp_code=" + corpCode;
    }

    // induty_code 앞 2자리(KSIC 중분류)로 업종명 조회 - 매핑에 없는 코드면 null
    private String toIndutyNm(String indutyCode) {
        if (indutyCode == null || indutyCode.length() < 2) return null;
        return INDUTY_NAME_MAP.get(indutyCode.substring(0, 2));
    }

    // KRX 상장종목 API 호출 후 KOSPI/KOSDAQ 필터링 및 스팩/리츠 제외
    private Map<String, KrxStockInfo> collectKrxStockInfo(String basDt) {

        Map<String, KrxStockInfo> stockMap = new HashMap<>();

        String url = UriComponentsBuilder.fromHttpUrl(KRX_LISTED_URL)
                .queryParam("serviceKey", stockApiKey)
                .queryParam("numOfRows", 4000) // 전체 종목 한 번에 수신
                .queryParam("pageNo", 1)
                .queryParam("resultType", "json")
                .queryParam("basDt", basDt)
                .build(false)
                .toUriString();

        try {
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            if (response == null) {
                log.warn("KRX 상장종목 API 응답 없음");
                return stockMap;
            }

            Map<String, Object> responseBody = (Map<String, Object>) response.get("response");
            Map<String, Object> body = (Map<String, Object>) responseBody.get("body");
            Map<String, Object> items = (Map<String, Object>) body.get("items");
            List<Map<String, Object>> itemList = (List<Map<String, Object>>) items.get("item");

            for (Map<String, Object> item : itemList) {
                // A 접두사 제거 (KRX srtnCd는 A로 시작)
                String srtnCd = ((String) item.get("srtnCd")).trim().replaceAll("^A", "");
                String itmsNm = ((String) item.get("itmsNm")).trim();
                String mrktCtgValue = ((String) item.get("mrktCtg")).trim();

                // KOSPI, KOSDAQ 외 제외 (KONEX 등)
                if (!"KOSPI".equals(mrktCtgValue) && !"KOSDAQ".equals(mrktCtgValue)) continue;

                // 스팩, 리츠 등 투자 분석 대상 아닌 종목 제외
                if (isExcludedStock(itmsNm)) continue;

                // KOSPI → Y, KOSDAQ → K
                String corpCls = "KOSPI".equals(mrktCtgValue) ? "Y" : "K";

                if (!srtnCd.isBlank()) {
                    stockMap.put(srtnCd, new KrxStockInfo(itmsNm, corpCls));
                }
            }

            log.info("KRX 전체 종목 수집 완료: {}건", stockMap.size());

        } catch (Exception e) {
            log.error("KRX 상장종목 수집 실패", e);
        }

        return stockMap;
    }

    // DART corpCode.xml에서 KRX 상장 종목에 해당하는 stockCode→corpCode 맵 추출
    private Map<String, String> collectCorpCodeMap(Set<String> listedStockCodes) throws Exception {

        String url = "https://opendart.fss.or.kr/api/corpCode.xml?crtfc_key=" + dartApiKey;
        byte[] zipData = restTemplate.getForObject(url, byte[].class);

        if (zipData == null) throw new RuntimeException("DART 응답 없음");

        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData));
        zis.getNextEntry();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(zis);

        NodeList nodeList = document.getElementsByTagName("list");
        Map<String, String> stockToCorpMap = new HashMap<>();

        for (int i = 0; i < nodeList.getLength(); i++) {
            Element element = (Element) nodeList.item(i);
            String stockCode = element.getElementsByTagName("stock_code")
                    .item(0).getTextContent().trim();

            // KRX 상장 종목에 없는 종목은 스킵
            if (stockCode.isBlank() || !listedStockCodes.contains(stockCode)) continue;

            String corpCode = element.getElementsByTagName("corp_code")
                    .item(0).getTextContent().trim();

            stockToCorpMap.put(stockCode, corpCode);
        }

        return stockToCorpMap;
    }

    // 종목명 기반 제외 여부 판단
    // 리츠: 부동산투자회사법상 상호 끝에 "리츠" 의무 표기 → endsWith로 오탐 방지 (메리츠, 블리츠 등 제외)
    // 스팩/기업인수목적: 자본시장법상 상호에 반드시 포함 → contains로 충분
    private boolean isExcludedStock(String corpName) {
        return corpName.endsWith("리츠")
                || corpName.contains("스팩")
                || corpName.contains("기업인수목적");
    }

    // KRX 종목 정보 담는 레코드
    private record KrxStockInfo(String corpName, String corpCls) {}
}