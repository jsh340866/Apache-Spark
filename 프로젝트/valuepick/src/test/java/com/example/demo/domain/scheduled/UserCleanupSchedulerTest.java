package com.example.demo.domain.scheduled;

import com.example.demo.domain.entity.User;
import com.example.demo.domain.entity.UserRole;
import com.example.demo.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class UserCleanupSchedulerTest {

    @Autowired
    private UserCleanupScheduler userCleanupScheduler;
    @Autowired
    private UserRepository userRepository;

    @Test
    void 탈퇴후_30일_지난_유저는_삭제된다() {
        User expiredUser = userRepository.save(User.builder()
                .email("cleanup-test-expired@test.com")
                .password("test")
                .nickname("cleanup-test-expired")
                .role(UserRole.USER)
                .createdAt(LocalDateTime.now().minusDays(40))
                .deletedAt(LocalDateTime.now().minusDays(31))
                .build());

        User recentlyDeletedUser = userRepository.save(User.builder()
                .email("cleanup-test-recent@test.com")
                .password("test")
                .nickname("cleanup-test-recent")
                .role(UserRole.USER)
                .createdAt(LocalDateTime.now().minusDays(10))
                .deletedAt(LocalDateTime.now().minusDays(5))
                .build());

        userCleanupScheduler.deleteExpiredUsers();

        assertThat(userRepository.findById(expiredUser.getId())).isEmpty();
        assertThat(userRepository.findById(recentlyDeletedUser.getId())).isPresent();

        userRepository.deleteById(recentlyDeletedUser.getId());
    }
}
