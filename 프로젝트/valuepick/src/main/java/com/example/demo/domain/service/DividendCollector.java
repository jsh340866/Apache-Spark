package com.example.demo.domain.service;

import com.example.demo.domain.dart.DartItem;
import com.example.demo.domain.dart.DartResponse;
import com.example.demo.domain.entity.Company;
import com.example.demo.domain.entity.DividendInfo;
import com.example.demo.domain.repository.CompanyRepository;
import com.example.demo.domain.repository.DividendInfoRepository;
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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DividendCollector {

    private final RestTemplate restTemplate;
    private final CompanyRepository companyRepository;
    private final DividendInfoRepository dividendInfoRepository;

    @Value("${dart.api.key}")
    private String apiKey;

    private static final int SLEEP_MS = 100; // DART API 호출 간격 (차단 방지)
    private static final int RETRY_COUNT = 1; // 실패 시 재시도 횟수

    // @Async로 백그라운드 실행 - 컨트롤러가 즉시 응답 반환 가능
    // dartExecutor 스레드풀 사용 - 재무 수집과 동시에 실행 가능 (corePoolSize=2)
    // 내부는 순차 처리 + SLEEP_MS 유지로 DART IP 차단 방지
    @Async("dartExecutor")
    public void collect(String year, String reprtCode) {

        int savedCount = 0;
        int page = 0;
        final int PAGE_SIZE = 100;

        while (true) {

            // 100건씩 페이징해서 Company 조회
            Pageable pageable = PageRequest.of(page, PAGE_SIZE);
            Page<Company> companyPage = companyRepository.findAll(pageable);
            List<Company> companies = companyPage.getContent();

            if (companies.isEmpty()) break;

            for (Company company : companies) {

                try {

                    // DART 배당 API 호출 (재시도 포함)
                    DartResponse dividendResponse = requestWithRetry(
                            buildDividendUrl(company.getCorpCode(), year, reprtCode));

                    log.info("배당 응답 status: {}", dividendResponse != null ? dividendResponse.getStatus() : "null");

                    // API 실패 또는 데이터 없으면 스킵
                    if (dividendResponse == null || !"000".equals(dividendResponse.getStatus())
                            || dividendResponse.getList() == null) {
                        log.warn("배당 API 실패 또는 데이터 없음: {}", company.getCorpName());
                        continue;
                    }

                    // 배당 데이터 파싱 후 DividendInfo 저장 (보통주/우선주 각각)
                    int count = saveDividendInfo(company, dividendResponse.getList());
                    savedCount += count;

                    log.info("배당 저장 완료: {} {}건", company.getCorpName(), count);
                    Thread.sleep(SLEEP_MS); // DART API 호출 간격 유지 (순차 처리)

                } catch (Exception e) {
                    log.error("처리 실패: {}", company.getCorpName(), e);
                }
            }

            if (!companyPage.hasNext()) break;
            page++;
        }

        log.info("DividendInfo 전체 저장 완료: {}건", savedCount);
    }

    // 배당 API 응답 파싱 → DividendInfo 엔티티 저장
    // 복합키(corpCode + dividendKind)로 보통주/우선주 각각 저장
    private int saveDividendInfo(Company company, List<DartItem> dividendItems) {

        int count = 0;

        Long commonAmount = null;   // 보통주 주당 배당금
        Long preferredAmount = null; // 우선주 주당 배당금
        LocalDateTime stlmDt = null; // 결산일

        for (DartItem item : dividendItems) {

            String se = item.getSe();
            String stockKnd = item.getStockKnd();

            // 보통주 주당 현금배당금 추출
            if ("주당 현금배당금(원)".equals(se) && "보통주".equals(stockKnd)) {
                commonAmount = parseLong(item.getThstrm());
            }

            // 우선주 주당 현금배당금 추출
            if ("주당 현금배당금(원)".equals(se) && "우선주".equals(stockKnd)) {
                preferredAmount = parseLong(item.getThstrm());
            }

            // 결산일 추출 - DART API 형식 "20231231" → LocalDateTime 변환
            if (item.getStlmDt() != null && !item.getStlmDt().isBlank()) {
                try {
                    stlmDt = LocalDateTime.parse(
                            item.getStlmDt().trim() + "T00:00:00",
                            DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                } catch (Exception ignored) {}
            }
        }

        // 보통주 저장 - dividendKind = "보통주"
        if (commonAmount != null) {
            dividendInfoRepository.save(DividendInfo.builder()
                    .corpCode(company.getCorpCode())
                    .dividendKind("보통주")
                    .dividendAmount(commonAmount)
                    .stlmDt(stlmDt)
                    .build());
            count++;
        }


        // 우선주 저장 - dividendKind = "우선주" (데이터 있을 때만)
        if (preferredAmount != null) {
            dividendInfoRepository.save(DividendInfo.builder()
                    .corpCode(company.getCorpCode())
                    .dividendKind("우선주")
                    .dividendAmount(preferredAmount)
                    .stlmDt(stlmDt)
                    .build());
            count++;
        }

        if (count == 0) {
            log.warn("배당금 데이터 없음, 스킵: {}", company.getCorpName());
        }

        return count;
    }

    // DART 배당 API 호출 (URL 직접 받는 버전, 재시도 포함)
    private DartResponse requestWithRetry(String url) {

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

    // DART 배당 API URL 생성
    private String buildDividendUrl(String corpCode, String year, String reprtCode) {
        return "https://opendart.fss.or.kr/api/alotMatter.json"
                + "?crtfc_key=" + apiKey
                + "&corp_code=" + corpCode
                + "&bsns_year=" + year
                + "&reprt_code=" + reprtCode;
    }

    // 숫자 문자열 → Long 변환 (null, 공백, "-" 처리)
    private Long parseLong(String value) {
        if (value == null || value.isBlank() || "-".equals(value.trim())) return null;
        try {
            return Long.parseLong(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}