package com.example.demo.domain.service;

import com.example.demo.domain.dto.ExchangeDto;
import com.example.demo.domain.entity.Exchange;
import com.example.demo.domain.repository.ExchangeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExchangeRateApiService {

    private final ExchangeRepository exchangeRepository;
    private final RestTemplate restTemplate;

    @Value("${koreaexim.api.key}")
    private String koreaeximApiKey;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // 연휴가 길어도 이전 영업일을 찾을 수 있도록 최대 10일 탐색
    private static final int MAX_PREVIOUS_DAY_LOOKUP = 10;

    // DART currency → 한국수출입은행 cur_unit 매핑 테이블
    // 100단위 통화(JPY, IDR)와 DART에서만 쓰는 CNY → CNH 매핑 처리
    private static final Map<String, String> CURRENCY_UNIT_MAP = Map.of(
            "KRW", "KRW",
            "USD", "USD",
            "CNY", "CNH",
            "JPY", "JPY(100)",
            "IDR", "IDR(100)"
    );

    // 100단위로 환율을 제공하는 통화 - dealBasR을 100으로 나눠야 1단위 환율이 됨
    private static final Set<String> UNIT_100_CURRENCIES = Set.of("JPY(100)", "IDR(100)");

    // 특정 날짜 기준 환율 수집 + 전일 대비 변동폭 계산 후 저장 (외부 진입점)
    @Transactional
    public List<ExchangeDto> fetchAndSaveExchangeRates(String searchDate) {
        return getExchangeRateChanges(searchDate);
    }

    // 오늘 날짜로 환율 수집 (스케줄러에서 호출하는 편의 메서드)
    @Transactional
    public List<ExchangeDto> fetchAndSaveExchangeRatesForToday() {
        return fetchAndSaveExchangeRates(LocalDate.now().format(DATE_FORMAT));
    }

    /**
     * DART currency 코드 기준으로 최신 KRW 환율 반환
     * KRW면 1.0 고정, 나머지는 DB에서 최신값 조회 후 단위 보정
     */
    public double getRateToKrw(String currency) {

        if ("KRW".equals(currency)) return 1.0;

        String curUnit = CURRENCY_UNIT_MAP.get(currency);

        if (curUnit == null) {
            log.warn("매핑되지 않은 통화: {} - 환율 조회 불가", currency);
            throw new IllegalStateException("지원하지 않는 통화: " + currency);
        }

        String finalCurUnit = curUnit;
        double rate = exchangeRepository.findTopByCurUnitOrderByBaseDateDesc(curUnit)
                .map(Exchange::getDealBasR)
                .orElseThrow(() -> new IllegalStateException("환율 정보 없음: " + finalCurUnit));

        if (UNIT_100_CURRENCIES.contains(curUnit)) {
            rate = rate / 100.0;
        }

        return rate;
    }

    // 당일 환율과 전 영업일 환율을 비교해 변동금액·변동률 계산 후 DB 저장
    @Transactional
    public List<ExchangeDto> getExchangeRateChanges(String searchDate) {

        // 당일 환율 API 호출
        List<Exchange> currentRates = callExchangeApi(searchDate);

        // 전 영업일 환율 조회 (DB 우선, 없으면 API 재호출)
        List<Exchange> previousRates = findPreviousBusinessDayRates(
                LocalDate.parse(searchDate, DATE_FORMAT));

        // 국가명을 키로 하여 전일 환율 맵 생성 (빠른 매핑을 위해)
        Map<String, Exchange> previousByCountry = previousRates.stream()
                .collect(Collectors.toMap(Exchange::getCountry, e -> e));

        List<Exchange> result = new ArrayList<>();

        for (Exchange current : currentRates) {

            Exchange previous = previousByCountry.get(current.getCountry());

            if (previous != null) {
                // 전일 대비 변동금액 = 당일 - 전일
                double changeAmount = current.getDealBasR() - previous.getDealBasR();
                // 전일 대비 변동률 = 변동금액 / 전일환율 × 100
                double changeRate = (changeAmount / previous.getDealBasR()) * 100;

                result.add(Exchange.builder()
                        .curUnit(current.getCurUnit())
                        .baseDate(current.getBaseDate())
                        .country(current.getCountry())
                        .dealBasR(current.getDealBasR())
                        .changeAmount(changeAmount)
                        .changeRate(changeRate)
                        .build());
            } else {
                // 전일 데이터 없으면 변동 없이 그대로 저장
                result.add(current);
            }
        }

        List<Exchange> saved = exchangeRepository.saveAll(result);
        return saved.stream().map(ExchangeDto::from).collect(Collectors.toList());
    }

    // 전 영업일 환율 조회: DB에 있으면 재사용, 없으면 API로 수집
    // callExchangeApi가 주말/공휴일 날짜에서 IllegalStateException을 던지므로
    // catch 후 하루 더 거슬러 올라가는 방식으로 영업일을 찾음
    private List<Exchange> findPreviousBusinessDayRates(LocalDate searchDate) {

        LocalDate candidate = searchDate.minusDays(1);

        for (int attempt = 0; attempt < MAX_PREVIOUS_DAY_LOOKUP; attempt++) {

            try {
                // DB에 해당 날짜 데이터가 있으면 API 호출 없이 반환
                List<Exchange> dbRates = exchangeRepository.findByBaseDate(candidate);

                if (!dbRates.isEmpty()) {
                    log.info("전 영업일 DB 조회 성공: {}", candidate);
                    return dbRates;
                }

                // DB에 없으면 API 직접 호출 (주말/공휴일이면 예외 발생 → catch에서 하루 더 이동)
                return callExchangeApi(candidate.format(DATE_FORMAT));

            } catch (IllegalStateException e) {
                candidate = candidate.minusDays(1);
            }
        }

        throw new IllegalStateException("전 영업일 환율 정보를 찾을 수 없습니다 (최대 " + MAX_PREVIOUS_DAY_LOOKUP + "일 탐색).");
    }

    // 한국수출입은행 API 호출 → Exchange 엔티티 리스트 반환
    // 주말/공휴일은 빈 응답 또는 result≠1로 IllegalStateException 발생
    private List<Exchange> callExchangeApi(String searchDate) {

        URI uri = UriComponentsBuilder
                .fromUriString("https://oapi.koreaexim.go.kr/site/program/financial/exchangeJSON")
                .queryParam("authkey", koreaeximApiKey)
                .queryParam("searchdate", searchDate)
                .queryParam("data", "AP01")
                .build(true)
                .toUri();

        List<Map<String, Object>> response = restTemplate.getForObject(uri, List.class);

        // 주말/공휴일이면 빈 배열 반환
        if (response == null || response.isEmpty()) {
            throw new IllegalStateException("해당 날짜(" + searchDate + ")는 주말/공휴일이거나 환율 정보가 없습니다.");
        }

        // result 코드 검증 (1 = 성공)
        Object firstResult = response.get(0).get("result");
        if (firstResult != null && !"1".equals(String.valueOf(firstResult))) {
            throw new IllegalStateException("환율 API 호출 실패 (result 코드: " + firstResult + ")");
        }

        LocalDate baseDate = LocalDate.parse(searchDate, DATE_FORMAT);
        List<Exchange> result = new ArrayList<>();

        // KRW는 API 응답에 없으므로 별도 고정값 추가
        result.add(buildKoreaExchange(baseDate));

        // cur_nm(국가명)을 country로, cur_unit을 curUnit으로 사용
        for (Map<String, Object> item : response) {
            try {
                result.add(buildExchange(
                        String.valueOf(item.get("cur_nm")),
                        item,
                        baseDate));
            } catch (Exception e) {
                log.warn("환율 파싱 실패: {}", item.get("cur_unit"));
            }
        }

        return result;
    }

    // 원화(KRW)는 환율 API 응답에 포함되지 않으므로 1.0으로 고정 생성
    private Exchange buildKoreaExchange(LocalDate baseDate) {
        return Exchange.builder()
                .curUnit("KRW")
                .baseDate(baseDate)
                .country("KOREA")
                .dealBasR(1.0)
                .changeAmount(0.0)
                .changeRate(0.0)
                .build();
    }

    // API 응답 항목(cur_unit, deal_bas_r 등) → Exchange 엔티티 변환
    // changeAmount·changeRate는 이 시점에 null — getExchangeRateChanges에서 채워짐
    private Exchange buildExchange(String country, Map<String, Object> item, LocalDate baseDate) {

        if (item == null) {
            throw new IllegalStateException(country + "의 환율 정보를 응답에서 찾을 수 없습니다.");
        }

        return Exchange.builder()
                .curUnit(String.valueOf(item.get("cur_unit")))
                .baseDate(baseDate)
                .country(country)
                .dealBasR(parseRate(item.get("deal_bas_r")))
                .changeAmount(null)
                .changeRate(null)
                .build();
    }

    // 콤마 포함 문자열 환율 → Double 변환 (null, 공백 처리)
    private Double parseRate(Object raw) {
        if (raw == null) return null;
        String text = String.valueOf(raw).replace(",", "").trim();
        if (text.isBlank()) return null;
        return Double.parseDouble(text);
    }
}