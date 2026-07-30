package com.example.demo.domain.service;

import com.example.demo.domain.dto.StockPriceDto;
import com.example.demo.domain.repository.CompanyRepository;
import com.example.demo.domain.repository.StockPriceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockPriceCollector {

    private final CompanyRepository companyRepository;
    private final StockPriceRepository stockPriceRepository;
    private final RestTemplate restTemplate;

    @Value("${stock.api.base-url}")
    private String baseUrl;

    @Value("${stock.api.key}")
    private String apiKey;

    public void collect(LocalDate startDate, LocalDate endDate) {

        // Company 테이블에 있는 종목만 저장 대상 (스팩·리츠는 DartCompanyCollector에서 이미 제외됨)
        Set<String> stockCodes = Set.copyOf(companyRepository.findAllIds());

        int totalSaved = 0;

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            try {
                totalSaved += collectByDate(date, stockCodes);
            } catch (Exception e) {
                log.error("날짜 수집 실패: {}", date, e);
            }
        }

        log.info("전체 저장 완료: {}건", totalSaved);
    }

    // 기준일 1회 호출로 전 종목 응답을 받아 Company에 등록된 종목만 필터링해서 저장
    private int collectByDate(LocalDate date, Set<String> stockCodes) throws InterruptedException {

        List<Map<String, Object>> items = requestApiWithRetry(date);
        int savedCount = 0;

        for (Map<String, Object> item : items) {
            StockPriceDto dto = toDto(item);

            if (dto == null || !stockCodes.contains(dto.getSrtnCd())) continue;

            stockPriceRepository.save(dto.toEntity());
            savedCount++;
        }

        log.info("저장 완료: {} {}건", date, savedCount);
        return savedCount;
    }

    // JSON item → StockPriceDto 변환
    private StockPriceDto toDto(Map<String, Object> item) {

        String basDtStr = (String) item.get("basDt");
        if (basDtStr == null || basDtStr.isBlank()) return null;

        return StockPriceDto.builder()
                .srtnCd(((String) item.get("srtnCd")).trim())
                .basDt(LocalDate.parse(basDtStr, DateTimeFormatter.ofPattern("yyyyMMdd")))
                .clpr(parseLong(item.get("clpr")))
                .mkp(parseLong(item.get("mkp")))
                .fltRt(parseDouble(item.get("fltRt")))
                .lstgStCnt(parseLong(item.get("lstgStCnt")))
                .mrktTotAmt(parseLong(item.get("mrktTotAmt")))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // 문자열/숫자 혼재 JSON 값 → Long 변환 (콤마 제거, null/빈값은 null 반환)
    private Long parseLong(Object value) {
        if (value == null) return null;
        try {
            return Long.parseLong(value.toString().replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // 문자열/숫자 혼재 JSON 값 → Double 변환 (등락률용, null/빈값은 null 반환)
    private Double parseDouble(Object value) {
        if (value == null) return null;
        try {
            return Double.parseDouble(value.toString().replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // 공공데이터 API 호출 (JSON) - 429 에러 발생 시 재시도, 최대 3회
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> requestApiWithRetry(LocalDate date) throws InterruptedException {

        int maxRetry = 3;
        String url = buildUrl(date);

        for (int i = 0; i < maxRetry; i++) {
            try {
                Map<String, Object> response = restTemplate.getForObject(url, Map.class);
                if (response == null) return List.of();

                Map<String, Object> responseBody = (Map<String, Object>) response.get("response");
                Map<String, Object> body = (Map<String, Object>) responseBody.get("body");
                Map<String, Object> items = (Map<String, Object>) body.get("items");

                if (items == null || items.get("item") == null) return List.of();

                return (List<Map<String, Object>>) items.get("item");

            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429) {
                    log.warn("429 Too Many Requests - 재시도 {}/{}: {}", i + 1, maxRetry, date);
                    Thread.sleep(3000L * (i + 1)); // 3초, 6초, 9초 대기
                } else {
                    throw e; // 429 외 다른 에러는 바로 던짐
                }
            }
        }
        throw new RuntimeException("최대 재시도 횟수 초과: " + date);
    }

    // API URL 생성 - 기준일(basDt) 1회 호출로 전 종목 수신 (likeSrtnCd 제거, numOfRows=4000)
    private String buildUrl(LocalDate date) {
        return UriComponentsBuilder
                .fromHttpUrl(baseUrl)
                .queryParam("serviceKey", apiKey)
                .queryParam("numOfRows", 4000)
                .queryParam("pageNo", 1)
                .queryParam("resultType", "json")
                .queryParam("basDt", date.format(DateTimeFormatter.ofPattern("yyyyMMdd")))
                .build(true)
                .toUriString();
    }
}
