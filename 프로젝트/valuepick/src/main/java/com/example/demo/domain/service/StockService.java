package com.example.demo.domain.service;

import com.example.demo.domain.dto.CompanyDto;
import com.example.demo.domain.dto.StockIndicatorDto;
import com.example.demo.domain.dto.StockPriceDto;
import com.example.demo.domain.entity.Company;
import com.example.demo.domain.entity.StockPrice;
import com.example.demo.domain.repository.CompanyRepository;
import com.example.demo.domain.repository.StockIndicatorRepository;
import com.example.demo.domain.repository.StockPriceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockService {

    private static final int PRICE_HISTORY_MONTHS = 3;

    private final CompanyRepository companyRepository;
    private final StockIndicatorRepository stockIndicatorRepository;
    private final StockPriceRepository stockPriceRepository;

    public Map<String, Object> getStockDetail(String stockCode) {
        Company company = companyRepository.findById(stockCode)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "존재하지 않는 종목코드입니다: " + stockCode));

        List<StockPrice> prices = stockPriceRepository.findBySrtnCdAndBasDtGreaterThanEqualOrderByBasDtAsc(
                stockCode, LocalDate.now().minusMonths(PRICE_HISTORY_MONTHS));

        if (prices.isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "시세 데이터가 없습니다: " + stockCode);
        }

        StockIndicatorDto indicatorDto = stockIndicatorRepository.findById(stockCode)
                .map(StockIndicatorDto::from)
                .orElse(null);

        List<StockPriceDto> priceHistory = prices.stream()
                .map(StockPriceDto::from)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("company", CompanyDto.from(company));
        result.put("indicator", indicatorDto);
        result.put("latestPrice", priceHistory.get(priceHistory.size() - 1));
        result.put("priceHistory", priceHistory);
        return result;
    }
}
