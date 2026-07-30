package com.example.demo.domain.service;

import com.example.demo.domain.dto.request.UpdateNicknameRequest;
import com.example.demo.domain.dto.request.UpdatePasswordRequest;
import com.example.demo.domain.dto.response.UserResponse;
import com.example.demo.domain.entity.User;
import com.example.demo.domain.repository.UserRepository;
import com.example.demo.domain.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    // 내 정보 조회
    @Transactional(readOnly = true)
    public UserResponse getMe(String email) {
        User user = userRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        return new UserResponse(user);
    }

    // 닉네임 수정
    @Transactional
    public UserResponse updateNickname(String email, UpdateNicknameRequest request) {
        User user = userRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        user.updateNickname(request.getNickname());
        return new UserResponse(user);
    }

    // 비밀번호 변경
    @Transactional
    public void updatePassword(String email, UpdatePasswordRequest request) {
        User user = userRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new IllegalArgumentException("현재 비밀번호가 올바르지 않습니다.");
        }
        user.updatePassword(passwordEncoder.encode(request.getNewPassword()));
    }

    // 회원탈퇴 (소프트 삭제 — DB에 30일 보관 후 스케줄러가 삭제)
    @Transactional
    public void withdraw(String email) {
        User user = userRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        refreshTokenRepository.findByEmail(email).ifPresent(refreshTokenRepository::delete);
        user.softDelete();
    }
}
