package com.example.demo.domain.scheduled;

import com.example.demo.domain.repository.StockPriceRepository;
import com.example.demo.domain.service.StockPriceCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;

@Component
@RequiredArgsConstructor
@Slf4j
public class StockPriceScheduler {

    private final StockPriceCollector stockPriceCollector;
    private final StockPriceRepository stockPriceRepository;

    // 월-금 새벽 1시 20분 수집 - 전 영업일자 기준 (월요일은 직전 영업일인 금요일자를 조회)
    @Scheduled(cron = "0 20 1 * * MON-FRI", zone = "Asia/Seoul")
    public void collectStockPrice() {
        try {
            LocalDate baseDate = LocalDate.now().minusDays(
                    LocalDate.now().getDayOfWeek() == DayOfWeek.MONDAY ? 3 : 1);
            log.info("[StockPriceScheduler] 주가 수집 시작 - date={}", baseDate);
            stockPriceCollector.collect(baseDate, baseDate);

            // 모멘텀 계산용 1개월전·12개월전 기준일 수집
            // 정확한 기준일이 휴장일(주말·공휴일)일 수 있어 최대 5일 전까지 범위로 수집,
            // 조회 시 StockPriceRepository의 "기준일 이전 최근 종가"로 가장 가까운 거래일 값을 사용
            collectMomentumReference(baseDate.minusMonths(1));
            collectMomentumReference(baseDate.minusMonths(12));
        } catch (Exception e) {
            log.error("[StockPriceScheduler] 주가 수집 실패", e);
        }
    }

    private void collectMomentumReference(LocalDate targetDate) {
        LocalDate rangeStart = targetDate.minusDays(5);
        log.info("[StockPriceScheduler] 모멘텀 기준일 수집 - target={}", targetDate);
        stockPriceCollector.collect(rangeStart, targetDate);
    }

    // 7일 이전 주가 데이터 새벽 2시 30분에 삭제
    @Scheduled(cron = "0 30 2 * * *", zone = "Asia/Seoul")
    public void deleteOldStockPrice() {
        try {
            LocalDate cutoff = LocalDate.now().minusDays(7);
            log.info("[StockPriceScheduler] 7일 이전 주가 삭제 - cutoff={}", cutoff);
            stockPriceRepository.deleteByBasDtBefore(cutoff);
        } catch (Exception e) {
            log.error("[StockPriceScheduler] 7일 이전 주가 삭제 실패", e);
        }
    }
}