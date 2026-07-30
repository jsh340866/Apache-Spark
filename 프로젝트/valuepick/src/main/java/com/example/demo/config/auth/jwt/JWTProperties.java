package com.example.demo.config.auth.jwt;

public class JWTProperties {

    public static final int ACCESS_TOKEN_EXPIRATION_TIME = 1000 * 60 * 30;      // AccessToken 만료시간: 30분 (밀리초 단위)
    public static final long REFRESH_TOKEN_EXPIRATION_TIME = 1000L * 60 * 60 * 24 * 7; // RefreshToken 만료시간: 7일 (밀리초 단위)

    // Authorization 헤더로 토큰을 전달
    public static final String TOKEN_PREFIX = "Bearer ";       // 헤더 값 앞에 붙는 prefix
    public static final String HEADER_STRING = "Authorization"; // 헤더 이름
}
