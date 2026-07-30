package com.example.demo.domain.service;

import com.example.demo.domain.dto.MarketIndexDto;
import com.example.demo.domain.entity.MarketIndex;
import com.example.demo.domain.repository.MarketIndexRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketIndexService {

    private final MarketIndexRepository marketIndexRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${krx.api.key}")
    private String krxApiKey;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // KRX 오픈API에서 코스피 지수 데이터 수집 후 DB 저장
    // basDd 형식: yyyyMMdd (예: 20260621), 영업일에만 데이터 존재
    public List<MarketIndexDto> fetchAndSave(String basDd) {

        URI uri = UriComponentsBuilder
                .fromUriString("https://data-dbg.krx.co.kr/svc/apis/idx/kospi_dd_trd")
                .queryParam("basDd", basDd)
                .build(true)
                .toUri();

        // KRX API는 AUTH_KEY 헤더 인증 방식 사용
        HttpHeaders headers = new HttpHeaders();
        headers.set("AUTH_KEY", krxApiKey);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));

        // 응답을 String으로 먼저 받아서 인증 실패 등으로 HTML이 와도 원문 확인 가능
        ResponseEntity<String> response = restTemplate.exchange(
                uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        String responseBody = response.getBody();

        log.info("[KRX KOSPI API 응답] status={}", response.getStatusCode());

        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            throw new IllegalStateException("KRX API 응답이 JSON 형식이 아닙니다. 응답 원문: "
                    + (responseBody != null ? responseBody.substring(0, Math.min(300, responseBody.length())) : "없음"));
        }

        JsonNode outBlock = root.get("OutBlock_1");

        // 영업일이 아니거나 데이터 없으면 예외 발생
        if (outBlock == null || !outBlock.isArray() || outBlock.isEmpty()) {
            throw new IllegalStateException("해당 날짜(" + basDd + ")는 영업일이 아니거나 코스피 지수 데이터가 없습니다.");
        }

        // JSON 응답 → MarketIndex 엔티티 변환 후 저장
        List<MarketIndex> marketIndexList = new ArrayList<>();
        for (JsonNode item : outBlock) {
            marketIndexList.add(toEntity(item));
        }

        List<MarketIndex> saved = marketIndexRepository.saveAll(marketIndexList);
        log.info("코스피 지수 저장 완료: {}건", saved.size());

        return saved.stream().map(MarketIndexDto::from).collect(Collectors.toList());
    }

    // 오늘 날짜 기준 코스피 지수 수집 후 저장
    public List<MarketIndexDto> fetchAndSaveForToday() {
        return fetchAndSave(LocalDate.now().format(DATE_FORMAT));
    }

    // JSON 응답 → MarketIndex 엔티티 변환
    private MarketIndex toEntity(JsonNode item) {

        // BAS_DD "20200414" → LocalDate 변환
        String basDdStr = textValue(item, "BAS_DD");
        LocalDate basDd = (basDdStr != null && !basDdStr.isBlank())
                ? LocalDate.parse(basDdStr, DATE_FORMAT)
                : null;

        return MarketIndex.builder()
                .basDd(basDd)                                                        // 기준일자
                .idxNm(textValue(item, "IDX_NM"))                                    // 지수명
                .clsprcIdx(parseDouble(textValue(item, "CLSPRC_IDX")))               // 종가지수
                .cmpprevddIdx(parseDouble(textValue(item, "CMPPREVDD_IDX")))         // 전일대비
                .flucRt(parseDouble(textValue(item, "FLUC_RT")))                     // 등락률
                .opnprcIdx(parseDouble(textValue(item, "OPNPRC_IDX")))               // 시가지수
                .mktcap(parseLong(textValue(item, "MKTCAP")))                        // 시가총액
                .build();
    }

    // JSON 노드에서 문자열 값 추출 유틸
    private String textValue(JsonNode item, String field) {
        JsonNode node = item.get(field);
        return (node == null || node.isNull()) ? null : node.asText();
    }

    // 콤마 포함 문자열 → Double 변환
    private Double parseDouble(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.replace(",", "").trim();
        return text.isBlank() ? null : Double.parseDouble(text);
    }

    // 콤마 포함 문자열 → Long 변환
    private Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.replace(",", "").trim();
        return text.isBlank() ? null : Long.parseLong(text);
    }
}