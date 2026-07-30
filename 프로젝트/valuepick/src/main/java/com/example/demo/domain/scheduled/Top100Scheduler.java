package com.example.demo.domain.scheduled;

import com.example.demo.domain.repository.Top100Repository;
import com.example.demo.domain.service.Top100Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@RequiredArgsConstructor
@Slf4j
public class Top100Scheduler {

    private final Top100Service top100Service;
    private final Top100Repository top100Repository;

    // StockPriceScheduler(새벽 1시 20분) 수집 완료 후 새벽 2시에 스코어 재계산
    @Scheduled(cron = "0 0 2 * * MON-FRI", zone = "Asia/Seoul")
    public void calculateTop100() {
        try {
            log.info("[Top100Scheduler] TOP100 스코어 계산 시작");
            top100Service.calculateAndSave();
            log.info("[Top100Scheduler] TOP100 스코어 계산 완료");
        } catch (Exception e) {
            log.error("[Top100Scheduler] TOP100 스코어 계산 실패", e);
        }
    }

    // 다른 삭제 작업(새벽 2시 30분)들 이후 새벽 2시 35분에 삭제
    @Scheduled(cron = "0 35 2 * * *", zone = "Asia/Seoul")
    public void deleteOldTop100() {
        try {
            LocalDate cutoff = LocalDate.now().minusDays(7);
            log.info("[Top100Scheduler] 7일 이전 TOP100 삭제 - cutoff={}", cutoff);
            top100Repository.deleteByBaseDtBefore(cutoff);
        } catch (Exception e) {
            log.error("[Top100Scheduler] 7일 이전 TOP100 삭제 실패", e);
        }
    }
}