package com.example.demo.domain.controller;

import com.example.demo.config.auth.jwt.TokenInfo;
import com.example.demo.domain.dto.request.LoginRequest;
import com.example.demo.domain.dto.request.RegisterRequest;
import com.example.demo.domain.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<String> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request.getEmail(), request.getPassword(), request.getNickname());
        return ResponseEntity.ok("회원가입이 완료되었습니다.");
    }

    @PostMapping("/login")
    public ResponseEntity<TokenInfo> login(@Valid @RequestBody LoginRequest request) {
        TokenInfo tokenInfo = authService.login(request.getEmail(), request.getPassword());
        return ResponseEntity.ok(tokenInfo);
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenInfo> refresh(@RequestBody java.util.Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        TokenInfo tokenInfo = authService.refresh(refreshToken);
        return ResponseEntity.ok(tokenInfo);
    }

}
