package com.example.demo.config.auth.jwt;

import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@RequiredArgsConstructor
public class JWTAuthorizationFilter extends OncePerRequestFilter {

    private final JWTTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = resolveToken(request);

        if (token != null) {
            try {
                if (jwtTokenProvider.validateToken(token)) {
                    Authentication authentication = jwtTokenProvider.getAuthentication(token);
                    if (authentication != null) {
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        log.info("Security Context 인증 정보 저장: {}", authentication.getName());
                    }
                }
            } catch (ExpiredJwtException e) {
                log.info("만료된 AccessToken");
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\": \"ACCESS_TOKEN_EXPIRED\"}");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    // Authorization 헤더에서 "Bearer " 제거 후 토큰 추출
    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(JWTProperties.HEADER_STRING);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(JWTProperties.TOKEN_PREFIX)) {
            return bearerToken.substring(JWTProperties.TOKEN_PREFIX.length());
        }
        return null;
    }
}
