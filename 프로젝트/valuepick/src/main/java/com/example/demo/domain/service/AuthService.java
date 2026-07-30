package com.example.demo.domain.service;

import com.example.demo.config.auth.jwt.JWTProperties;
import com.example.demo.config.auth.jwt.JWTTokenProvider;
import com.example.demo.config.auth.jwt.TokenInfo;
import com.example.demo.domain.entity.RefreshToken;
import com.example.demo.domain.entity.User;
import com.example.demo.domain.entity.UserRole;
import com.example.demo.domain.repository.RefreshTokenRepository;
import com.example.demo.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JWTTokenProvider jwtTokenProvider;

    @Transactional
    public void register(String email, String password, String nickname) {
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("이미 사용 중인 이메일입니다.");
        }

        User user = User.builder()
                .email(email)
                .password(passwordEncoder.encode(password))
                .nickname(nickname)
                .role(UserRole.USER)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        userRepository.save(user);
        log.info("회원가입 완료: {}", email);
    }

    @Transactional
    public TokenInfo login(String email, String password) {
        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.isDeleted()) throw new IllegalArgumentException("탈퇴한 계정입니다.");
        });

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, password)
        );

        TokenInfo tokenInfo = jwtTokenProvider.generateToken(authentication);

        // RefreshToken DB 저장 (기존 토큰 있으면 갱신, 없으면 새로 저장)
        LocalDateTime expiresAt = LocalDateTime.now()
                .plusSeconds(JWTProperties.REFRESH_TOKEN_EXPIRATION_TIME / 1000);

        Optional<RefreshToken> existing = refreshTokenRepository.findByEmail(email);
        if (existing.isPresent()) {
            existing.get().updateToken(tokenInfo.getRefreshToken(), expiresAt);
        } else {
            refreshTokenRepository.save(RefreshToken.builder()
                    .email(email)
                    .token(tokenInfo.getRefreshToken())
                    .expiresAt(expiresAt)
                    .build());
        }

        log.info("로그인 성공, 토큰 발급: {}", email);
        return tokenInfo;
    }

    @Transactional
    public TokenInfo refresh(String refreshToken) {
        RefreshToken stored = refreshTokenRepository.findByToken(refreshToken)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 리프레시 토큰입니다."));

        if (stored.getExpiresAt().isBefore(LocalDateTime.now())) {
            refreshTokenRepository.delete(stored);
            throw new IllegalArgumentException("만료된 리프레시 토큰입니다. 다시 로그인해주세요.");
        }

        // 새 토큰 발급
        TokenInfo tokenInfo = jwtTokenProvider.generateTokenByEmail(stored.getEmail());

        // RefreshToken 갱신
        LocalDateTime expiresAt = LocalDateTime.now()
                .plusSeconds(JWTProperties.REFRESH_TOKEN_EXPIRATION_TIME / 1000);
        stored.updateToken(tokenInfo.getRefreshToken(), expiresAt);

        log.info("토큰 재발급: {}", stored.getEmail());
        return tokenInfo;
    }
}
