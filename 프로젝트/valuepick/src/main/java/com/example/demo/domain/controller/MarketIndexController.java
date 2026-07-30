package com.example.demo.domain.controller;

import com.example.demo.domain.dto.MarketIndexDto;
import com.example.demo.domain.service.MarketIndexService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/market")
public class MarketIndexController {

    private final MarketIndexService marketIndexService;

    // 특정 날짜 코스피 지수 수집 - 날짜 형식: yyyyMMdd (예: 20260613)
    @GetMapping("/collect/{basDd}")
    public ResponseEntity<List<MarketIndexDto>> collect(@PathVariable String basDd) {
        List<MarketIndexDto> result = marketIndexService.fetchAndSave(basDd);
        return ResponseEntity.ok(result);
    }

    // 오늘 날짜 코스피 지수 수집
    @GetMapping("/collect/today")
    public ResponseEntity<List<MarketIndexDto>> collectToday() {
        List<MarketIndexDto> result = marketIndexService.fetchAndSaveForToday();
        return ResponseEntity.ok(result);
    }
}