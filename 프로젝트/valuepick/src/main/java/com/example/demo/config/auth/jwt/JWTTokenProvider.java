package com.example.demo.config.auth.jwt;

import com.example.demo.config.auth.PrincipalDetails;
import com.example.demo.domain.dto.UserDto;
import com.example.demo.domain.entity.User;
import com.example.demo.domain.entity.UserRole;
import com.example.demo.domain.repository.UserRepository;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class JWTTokenProvider {

    private final UserRepository userRepository;

    @Value("${jwt.secret}")
    private String secretKey;

    private Key key;

    // 앱 시작 시 한 번 실행 - properties 의 jwt.secret 으로 서명 키 생성 (재시작해도 동일 키 유지)
    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
        log.info("JWTTokenProvider init - key loaded from properties");
    }

    // 로그인 성공 시 AccessToken + RefreshToken 생성
    public TokenInfo generateToken(Authentication authentication) {

        // authentication 에서 role 꺼내기 -> "ROLE_USER"
        String authorities = authentication.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));

        long now = new Date().getTime();

        // AccessToken: username, role 을 payload(claim) 에 담아 생성
        String accessToken = Jwts.builder()
                .setSubject(authentication.getName())
                .setExpiration(new Date(now + JWTProperties.ACCESS_TOKEN_EXPIRATION_TIME))
                .signWith(key, SignatureAlgorithm.HS256)
                .claim("username", authentication.getName())
                .claim("auth", authorities)
                .compact();

        // RefreshToken: AccessToken 만료 시 재발급 용도 - payload 최소화
        String refreshToken = Jwts.builder()
                .setSubject("Refresh_Token")
                .setExpiration(new Date(now + JWTProperties.REFRESH_TOKEN_EXPIRATION_TIME))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();

        return TokenInfo.builder()
                .grantType("Bearer")
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    // 토큰 -> Authentication 객체 변환 (Filter 에서 SecurityContext 에 저장할 때 사용)
    public Authentication getAuthentication(String accessToken) throws ExpiredJwtException {

        // 토큰 파싱 -> payload(claims) 추출
        Claims claims = Jwts.parser()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(accessToken)
                .getBody();

        String username = (String) claims.get("username");
        String auth = (String) claims.get("auth"); // "ROLE_USER"

        // role 문자열 -> GrantedAuthority 컬렉션 변환
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        for (String role : auth.split(",")) {
            authorities.add(new SimpleGrantedAuthority(role));
        }
        
        // 관심종목
        User user = userRepository.findByEmail(username).orElse(null);
        if (user == null) {
          return null;
        }
      
        // 회원탈퇴
        if (!userRepository.existsByEmailAndDeletedAtIsNull(username)) {
            return null;
        }

        UserDto userDto = UserDto.builder()
                .id(user.getId())
                .email(username)
                .role(UserRole.valueOf(auth.replace("ROLE_", "")))
                .build();

        PrincipalDetails principalDetails = new PrincipalDetails(userDto);

        return new UsernamePasswordAuthenticationToken(principalDetails, null, authorities);
    }

    // 토큰 유효성 검증 - 만료 시 ExpiredJwtException 던짐
    public boolean validateToken(String token) throws ExpiredJwtException {
        try {
            Jwts.parser().setSigningKey(key).build().parseClaimsJws(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.info("[ExpiredJwtException] {}", e.getMessage());
            throw new ExpiredJwtException(null, null, null);
        }
    }

    // RefreshToken 재발급 시 email로 직접 토큰 생성
    public TokenInfo generateTokenByEmail(String email) {
        if (!userRepository.existsByEmailAndDeletedAtIsNull(email)) {
            return null;
        }

        User user = userRepository.findByEmailAndDeletedAtIsNull(email).get();
        String authority = "ROLE_" + user.getRole().name();

        long now = new Date().getTime();

        String accessToken = Jwts.builder()
                .setSubject(email)
                .setExpiration(new Date(now + JWTProperties.ACCESS_TOKEN_EXPIRATION_TIME))
                .signWith(key, SignatureAlgorithm.HS256)
                .claim("username", email)
                .claim("auth", authority)
                .compact();

        String refreshToken = Jwts.builder()
                .setSubject("Refresh_Token")
                .setExpiration(new Date(now + JWTProperties.REFRESH_TOKEN_EXPIRATION_TIME))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();

        return TokenInfo.builder()
                .grantType("Bearer")
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    public Key getKey() {
        return this.key;
    }
}
