package com.example.demo.domain.controller;

import com.example.demo.domain.service.FinancialIndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/admin/indicator")
public class FinancialIndicatorController {

    private final FinancialIndicatorService financialIndicatorService;

    // 전체 회사 투자지표 계산 실행 - year를 기준으로 FinancialStatement 데이터 조회 후 StockIndicator 저장
    @GetMapping("/calculate/{year}/{reprtCode}")
    public ResponseEntity<String> calculate(@PathVariable String year,@PathVariable String reprtCode) {
        log.info("지표 계산 요청: year={}", year);
        financialIndicatorService.calculateAll(year,reprtCode);
        return ResponseEntity.ok(year + "년 지표 계산 완료");
    }
}
