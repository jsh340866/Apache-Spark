package com.example.demo.domain.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.domain.service.DartCompanyCollector;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class CompanyController {

    private final DartCompanyCollector collector;

    @GetMapping("/company/load")
    public ResponseEntity<String> load(@RequestParam String basDt) {
        if (basDt == null || !basDt.matches("\\d{8}")) {
            return ResponseEntity.badRequest().body("날짜 형식이 올바르지 않습니다. yyyyMMdd 형식으로 입력하세요.");
        }
        collector.collectCompanies(basDt);
        return ResponseEntity.ok("수집 시작 (비동기)");
    }

}
