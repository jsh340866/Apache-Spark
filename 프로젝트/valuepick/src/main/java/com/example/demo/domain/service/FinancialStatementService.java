package com.example.demo.domain.service;

import com.example.demo.domain.dto.FinancialStatementDto;
import com.example.demo.domain.repository.CompanyRepository;
import com.example.demo.domain.repository.FinancialStatementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FinancialStatementService {

    private final CompanyRepository companyRepository;
    private final FinancialStatementRepository financialStatementRepository;

    public List<FinancialStatementDto> getFinancialStatements(String stockCode) {
        if (!companyRepository.existsById(stockCode)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "존재하지 않는 종목코드입니다: " + stockCode);
        }

        return financialStatementRepository.findByCompany_StockCodeOrderByBsnsYearDesc(stockCode).stream()
                .map(FinancialStatementDto::from)
                .toList();
    }
}
