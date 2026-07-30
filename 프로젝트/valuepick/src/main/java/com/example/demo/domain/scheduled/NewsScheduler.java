package com.example.demo.domain.scheduled;

import com.example.demo.domain.service.NewsCrawlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NewsScheduler {

    private final NewsCrawlService newsCrawlService;

    // 매시 정각에 전체 종목 뉴스를 수집합니다.
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Seoul")
    public void crawlNews() {
        try {
            newsCrawlService.crawlAndSaveAll();
        } catch (Exception e) {
            log.error("뉴스 수집 스케줄러 실행 중 오류 발생: {}", e.getMessage(), e);
        }
    }
}
