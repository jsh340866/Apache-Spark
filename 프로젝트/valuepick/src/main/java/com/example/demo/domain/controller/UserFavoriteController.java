package com.example.demo.domain.controller;

import com.example.demo.config.auth.PrincipalDetails;
import com.example.demo.domain.service.UserFavoriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/favorites")
@RequiredArgsConstructor
public class UserFavoriteController {

    private final UserFavoriteService userFavoriteService;

    @GetMapping
    public List<Map<String, Object>> getFavorites(@AuthenticationPrincipal PrincipalDetails principal) {
        return userFavoriteService.getFavorites(principal.getUserDto().getId());
    }

    @PostMapping("/{stockCode}")
    public void addFavorite(@AuthenticationPrincipal PrincipalDetails principal, @PathVariable String stockCode) {
        userFavoriteService.addFavorite(principal.getUserDto().getId(), stockCode);
    }

    @DeleteMapping("/{stockCode}")
    public void removeFavorite(@AuthenticationPrincipal PrincipalDetails principal, @PathVariable String stockCode) {
        userFavoriteService.removeFavorite(principal.getUserDto().getId(), stockCode);
    }
}
