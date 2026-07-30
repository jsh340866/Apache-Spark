package com.example.demo.domain.controller;

import com.example.demo.domain.dto.ExchangeDto;
import com.example.demo.domain.service.ExchangeRateApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/exchange")
public class ExchangeController {

    private final ExchangeRateApiService exchangeRateApiService;

    // 특정 날짜 환율 수집 및 저장 - 날짜 형식: yyyyMMdd (예: 20260621)
    @GetMapping("/collect/{date}")
    public ResponseEntity<List<ExchangeDto>> collect(@PathVariable String date) {
        List<ExchangeDto> result = exchangeRateApiService.fetchAndSaveExchangeRates(date);
        return ResponseEntity.ok(result);
    }

    // 오늘 날짜 환율 수집 및 저장
    @GetMapping("/collect/today")
    public ResponseEntity<List<ExchangeDto>> collectToday() {
        List<ExchangeDto> result = exchangeRateApiService.fetchAndSaveExchangeRatesForToday();
        return ResponseEntity.ok(result);
    }

    // 특정 날짜 환율을 전일 대비 등락률/등락폭 계산 후 저장 - 날짜 형식: yyyyMMdd (예: 20260621)
    @GetMapping("/changes/{date}")
    public ResponseEntity<List<ExchangeDto>> getChanges(@PathVariable String date) {
        List<ExchangeDto> result = exchangeRateApiService.getExchangeRateChanges(date);
        return ResponseEntity.ok(result);
    }
}