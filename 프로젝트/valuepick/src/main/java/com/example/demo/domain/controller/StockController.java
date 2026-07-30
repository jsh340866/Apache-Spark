package com.example.demo.domain.controller;

import com.example.demo.domain.dto.NewsDto;
import com.example.demo.domain.repository.CompanyRepository;
import com.example.demo.domain.repository.StockPriceRepository;
import com.example.demo.domain.service.NewsService;
import com.example.demo.domain.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
import java.util.*;
import java.util.stream.Collectors;



@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;
    private final NewsService newsService;
    private final CompanyRepository companyRepository;
    private final StockPriceRepository stockPriceRepository;

    @GetMapping("/search")
    public List<Map<String, Object>> searchCompanies(@RequestParam String q) {
        return companyRepository
                .findTop20ByCorpNameContainingOrStockCodeContainingOrderByCorpNameAsc(q, q)
                .stream()
                .map(c -> {
                    Long price = stockPriceRepository.findTopBySrtnCdOrderByBasDtDesc(c.getStockCode())
                            .map(sp -> sp.getClpr())
                            .orElse(null);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("stockCode", c.getStockCode());
                    item.put("corpName", c.getCorpName());
                    item.put("currentPrice", price);
                    return item;
                })
                .collect(Collectors.toList());
    }

    @GetMapping("/{stockCode}")
    public Map<String, Object> getStockDetail(@PathVariable String stockCode) {
        return stockService.getStockDetail(stockCode);
    }

    @GetMapping("/{stockCode}/news")
    public Page<NewsDto> getStockNews(@PathVariable String stockCode, @RequestParam(defaultValue = "0") int page) {
        return newsService.getNews(stockCode, page);
    }
}
