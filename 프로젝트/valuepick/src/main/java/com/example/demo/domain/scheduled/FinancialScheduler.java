package com.example.demo.domain.scheduled;

import com.example.demo.domain.service.DartFinancialCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@RequiredArgsConstructor
@Slf4j
public class FinancialScheduler {

    private final DartFinancialCollector dartFinancialCollector;

    // 사업보고서 (11011) - 4월 1일 새벽 1시 수집. 지표계산은 IndicatorScheduler가 평일 매일 담당
    @Scheduled(cron = "0 0 1 1 4 *", zone = "Asia/Seoul")
    public void collectAnnual() {
        try {
            String year = String.valueOf(LocalDate.now().getYear() - 1);
            log.info("[FinancialScheduler] 사업보고서 수집 시작 - year={}", year);
            dartFinancialCollector.collect(year, "11011");
        } catch (Exception e) {
            log.error("[FinancialScheduler] 사업보고서 수집 실패", e);
        }
    }

//    @Scheduled(cron = "0 0 1 1 6 *")
//    public void collectQ1() {
//        try {
//            String year = String.valueOf(LocalDate.now().getYear());
//            log.info("[FinancialScheduler] 1분기보고서 수집 시작 - year={}", year);
//            dartFinancialCollector.collect(year, "11013");
//        } catch (Exception e) {
//            log.error("[FinancialScheduler] 1분기보고서 수집 실패", e);
//        }
//    }
//
//    @Scheduled(cron = "0 0 3 1 6 *")
//    public void calculateQ1() {
//        try {
//            String year = String.valueOf(LocalDate.now().getYear());
//            log.info("[FinancialScheduler] 1분기보고서 지표계산 시작 - year={}", year);
//            financialIndicatorService.calculateAll(year, "11013");
//        } catch (Exception e) {
//            log.error("[FinancialScheduler] 1분기보고서 지표계산 실패", e);
//        }
//    }
//
//    @Scheduled(cron = "0 0 1 1 9 *")
//    public void collectHalf() {
//        try {
//            String year = String.valueOf(LocalDate.now().getYear());
//            log.info("[FinancialScheduler] 반기보고서 수집 시작 - year={}", year);
//            dartFinancialCollector.collect(year, "11012");
//        } catch (Exception e) {
//            log.error("[FinancialScheduler] 반기보고서 수집 실패", e);
//        }
//    }
//
//    @Scheduled(cron = "0 0 3 1 9 *")
//    public void calculateHalf() {
//        try {
//            String year = String.valueOf(LocalDate.now().getYear());
//            log.info("[FinancialScheduler] 반기보고서 지표계산 시작 - year={}", year);
//            financialIndicatorService.calculateAll(year, "11012");
//        } catch (Exception e) {
//            log.error("[FinancialScheduler] 반기보고서 지표계산 실패", e);
//        }
//    }
//
//    @Scheduled(cron = "0 0 1 1 12 *")
//    public void collectQ3() {
//        try {
//            String year = String.valueOf(LocalDate.now().getYear());
//            log.info("[FinancialScheduler] 3분기보고서 수집 시작 - year={}", year);
//            dartFinancialCollector.collect(year, "11014");
//        } catch (Exception e) {
//            log.error("[FinancialScheduler] 3분기보고서 수집 실패", e);
//        }
//    }
//
//    @Scheduled(cron = "0 0 3 1 12 *")
//    public void calculateQ3() {
//        try {
//            String year = String.valueOf(LocalDate.now().getYear());
//            log.info("[FinancialScheduler] 3분기보고서 지표계산 시작 - year={}", year);
//            financialIndicatorService.calculateAll(year, "11014");
//        } catch (Exception e) {
//            log.error("[FinancialScheduler] 3분기보고서 지표계산 실패", e);
//        }
//    }
}