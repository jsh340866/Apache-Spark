package com.example.demo.domain.controller;

import com.example.demo.domain.dto.FinancialStatementDto;
import com.example.demo.domain.service.FinancialStatementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/stocks/{stockCode}/financial-statements")
@RequiredArgsConstructor
public class FinancialStatementController {

    private final FinancialStatementService financialStatementService;

    @GetMapping
    public List<FinancialStatementDto> getFinancialStatements(@PathVariable String stockCode) {
        return financialStatementService.getFinancialStatements(stockCode);
    }
}
