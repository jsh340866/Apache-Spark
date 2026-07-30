package com.example.demo.domain.controller;

import com.example.demo.domain.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;

@SpringBootTest
class CollectorTest {

    @Autowired
    private DartCompanyCollector dartCompanyCollector;
    @Autowired
    private DartFinancialCollector dartFinancialCollector;
    @Autowired
    private DividendCollector dividendCollector;
    @Autowired
    private StockPriceCollector stockPriceCollector;
    @Autowired
    private ExchangeRateApiService exchangeRateApiService;
    @Autowired
    private MarketIndexService marketIndexService;
    @Autowired
    private FinancialIndicatorService financialIndicatorService;
    @Autowired
    private Top100Service top100Service;



    @Test
    public void t1_기업정보수집() {
        dartCompanyCollector.collectCompanies("20260701"); // 어제 기준
    }

    @Test
    public void t2_재무제표수집() {
        dartFinancialCollector.collect("2025", "11011"); // 3개년 한번에 저장
    }

//    @Test
//    public void t2_1_재무제표수집_1개년() {
//        dartFinancialCollector.collectCurrentOnly("2025", "11011"); // 1개년만 (나중에 스캐줄용)
//    }

    @Test
    public void t3_배당금수집() {
        dividendCollector.collect("2025", "11011");
    }

    @Test
    public void t4_주가수집() {
        // 최신 주가(오늘 지표계산의 기준가 역할) - 어제(2026-07-01)까지 최근 구간
        LocalDate date = LocalDate.of(2026, 6,25);
        LocalDate endDate = LocalDate.of(2026, 7,1);
        stockPriceCollector.collect(date, endDate);

        // 모멘텀/F-Score(신주발행 여부) 테스트용 1개월전·12개월전 구간
        // 기준일은 어제(2026-07-01) 기준, 휴장일 대비 5일 여유
        stockPriceCollector.collect(LocalDate.of(2026, 5, 27), LocalDate.of(2026, 6, 1));
        stockPriceCollector.collect(LocalDate.of(2025, 6, 26), LocalDate.of(2025, 7, 1));
    }

    @Test
    public void t5_환율수집() {
        exchangeRateApiService.fetchAndSaveExchangeRates("20260701"); // 어제 기준
    }


    @Test
    public void t6_코스피지수수집() {
        marketIndexService.fetchAndSave("20260701"); // 어제 기준
    }

    @Test
    public void t7_지표계산() {
        financialIndicatorService.calculateAll("2025", "11011");
    }

    @Test
    public void t8_Top100스코어계산() {
        top100Service.calculateAndSave();
    }
}
