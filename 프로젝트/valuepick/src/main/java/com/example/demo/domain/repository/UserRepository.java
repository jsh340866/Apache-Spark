package com.example.demo.domain.repository;

import com.example.demo.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    boolean existsByEmailAndDeletedAtIsNull(String email);

    // 탈퇴 계정 포함 전체 조회 — 30일 내 재가입 차단, 탈퇴 계정 로그인 시 안내 메시지용
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("SELECT u FROM User u WHERE u.deletedAt IS NOT NULL AND u.deletedAt < :cutoff")
    List<User> findSoftDeletedBefore(LocalDateTime cutoff);
}
