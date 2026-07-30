package com.example.demo.domain.scheduled;

import com.example.demo.domain.service.FinancialIndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@RequiredArgsConstructor
@Slf4j
public class IndicatorScheduler {

    private final FinancialIndicatorService financialIndicatorService;

    // StockPriceScheduler(새벽 1시 20분) 수집 완료 후, Top100Scheduler(새벽 2시) 이전 - 평일 새벽 1시 50분
    // 재무제표(FinancialStatement)는 연 1회(4월 1일)만 갱신되지만, PER/PBR/모멘텀 등은 종가에 연동되므로 매일 재계산 필요
    @Scheduled(cron = "0 50 1 * * MON-FRI", zone = "Asia/Seoul")
    public void calculateDaily() {
        try {
            String year = String.valueOf(activeYear());
            log.info("[IndicatorScheduler] 지표계산 시작 - year={}", year);
            financialIndicatorService.calculateAll(year, "11011");
        } catch (Exception e) {
            log.error("[IndicatorScheduler] 지표계산 실패", e);
        }
    }

    // 사업보고서는 매년 4월 1일에 전년도분이 수집되므로, 그 전엔 재작년(-2) 데이터가, 그 후엔 작년(-1) 데이터가 최신 재무제표임
    private int activeYear() {
        LocalDate today = LocalDate.now();
        LocalDate collectionDate = LocalDate.of(today.getYear(), 4, 1);
        return today.isBefore(collectionDate) ? today.getYear() - 2 : today.getYear() - 1;
    }
}
