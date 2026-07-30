package com.example.demo.domain.scheduled;

import com.example.demo.domain.service.DartCompanyCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
@Slf4j
public class CompanyScheduler {

    private final DartCompanyCollector dartCompanyCollector;

    // 매년 1월 1일 새벽 1시
    @Scheduled(cron = "0 0 1 1 1 *", zone = "Asia/Seoul")
    public void collectCompany() {
        try {
            String basDt = LocalDate.now().minusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            log.info("[CompanyScheduler] 기업정보 수집 시작");
            dartCompanyCollector.collectCompanies(basDt);
        } catch (Exception e) {
            log.error("[CompanyScheduler] 기업정보 수집 실패", e);
        }
    }
}