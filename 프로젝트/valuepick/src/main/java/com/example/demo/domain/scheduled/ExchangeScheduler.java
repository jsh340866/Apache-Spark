package com.example.demo.domain.scheduled;

import com.example.demo.domain.repository.ExchangeRepository;
import com.example.demo.domain.service.ExchangeRateApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExchangeScheduler {

    private final ExchangeRateApiService exchangeRateApiService;
    private final ExchangeRepository exchangeRepository;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // 월-금 새벽 1시 수집 - 환율은 당일 오전 11시경 고시되므로 전 영업일자 조회
    // (월요일은 직전 영업일인 금요일자를 조회)
    @Scheduled(cron = "0 0 1 * * MON-FRI", zone = "Asia/Seoul")
    public void collectExchangeRate() {
        try {
            LocalDate targetDate = LocalDate.now().minusDays(
                    LocalDate.now().getDayOfWeek() == DayOfWeek.MONDAY ? 3 : 1);
            log.info("[ExchangeScheduler] 환율 수집 시작 - date={}", targetDate);
            exchangeRateApiService.fetchAndSaveExchangeRates(targetDate.format(DATE_FORMAT));
            log.info("[ExchangeScheduler] 환율 수집 완료");
        } catch (Exception e) {
            log.error("[ExchangeScheduler] 환율 수집 실패", e);
        }
    }

    // 7일 이전 환율 데이터 새벽 2시 30분에 삭제
    @Scheduled(cron = "0 30 2 * * *", zone = "Asia/Seoul")
    public void deleteOldExchangeRate() {
        try {
            LocalDate cutoff = LocalDate.now().minusDays(7);
            log.info("[ExchangeScheduler] 7일 이전 환율 삭제 - cutoff={}", cutoff);
            exchangeRepository.deleteByBaseDateBefore(cutoff);
        } catch (Exception e) {
            log.error("[ExchangeScheduler] 7일 이전 환율 삭제 실패", e);
        }
    }
}