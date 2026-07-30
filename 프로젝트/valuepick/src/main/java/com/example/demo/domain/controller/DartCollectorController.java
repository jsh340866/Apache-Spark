package com.example.demo.domain.controller;

import com.example.demo.domain.service.DartFinancialCollector;
import com.example.demo.domain.service.DividendCollector;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/dart")
public class DartCollectorController {

    private final DartFinancialCollector dartFinancialCollector;
    private final DividendCollector dividendCollector;

    // DART 재무제표 수집 - reportCode 기본값 11011(사업보고서)
    // 11011 → 사업보고서, 11012 → 반기보고서, 11013 → 1분기, 11014 → 3분기
    @GetMapping("/financial/{year}")
    public String collectFinancial(
            @PathVariable String year,
            @RequestParam(defaultValue = "11011") String reportCode
    ) {
        dartFinancialCollector.collect(year, reportCode);
        return "Financial 데이터 수집 완료";
    }

    // DART 배당 데이터 수집 - reportCode 기본값 11011(사업보고서)
    @GetMapping("/dividend/{year}")
    public String collectDividend(
            @PathVariable String year,
            @RequestParam(defaultValue = "11011") String reportCode
    ) {
        dividendCollector.collect(year, reportCode);
        return "Dividend 데이터 수집 완료";
    }
}
