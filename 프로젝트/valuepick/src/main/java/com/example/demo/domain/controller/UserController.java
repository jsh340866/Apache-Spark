package com.example.demo.domain.controller;

import com.example.demo.domain.dto.request.UpdateNicknameRequest;
import com.example.demo.domain.dto.request.UpdatePasswordRequest;
import com.example.demo.domain.dto.response.UserResponse;
import com.example.demo.domain.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    // 내 정보 조회
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getMe(@AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(userService.getMe(userDetails.getUsername()));
    }

    // 닉네임 수정
    @PutMapping("/me")
    public ResponseEntity<UserResponse> updateNickname(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody UpdateNicknameRequest request) {
        return ResponseEntity.ok(userService.updateNickname(userDetails.getUsername(), request));
    }

    // 비밀번호 변경
    @PutMapping("/me/password")
    public ResponseEntity<Void> updatePassword(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody UpdatePasswordRequest request) {
        userService.updatePassword(userDetails.getUsername(), request);
        return ResponseEntity.ok().build();
    }

    // 회원탈퇴
    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal UserDetails userDetails) {
        userService.withdraw(userDetails.getUsername());
        return ResponseEntity.ok().build();
    }
}
