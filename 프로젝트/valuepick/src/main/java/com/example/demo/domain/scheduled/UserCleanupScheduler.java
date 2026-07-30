package com.example.demo.domain.scheduled;

import com.example.demo.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserCleanupScheduler {

    private final UserRepository userRepository;

    // 매일 자정 — 탈퇴 후 30일 지난 유저 완전 삭제
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    @Transactional
    public void deleteExpiredUsers() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        var targets = userRepository.findSoftDeletedBefore(cutoff);
        if (!targets.isEmpty()) {
            userRepository.deleteAll(targets);
            log.info("탈퇴 유저 {}명 영구 삭제 완료 (기준: {})", targets.size(), cutoff);
        }
    }
}
