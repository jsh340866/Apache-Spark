package com.example.demo.config.auth.jwt;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TokenInfo {
    private String grantType;    // "Bearer"
    private String accessToken;
    private String refreshToken;
}
