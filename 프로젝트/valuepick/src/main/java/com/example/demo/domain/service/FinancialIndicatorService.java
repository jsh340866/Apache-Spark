package com.example.demo.domain.service;

import com.example.demo.domain.entity.Company;
import com.example.demo.domain.entity.DividendInfo;
import com.example.demo.domain.entity.FinancialStatement;
import com.example.demo.domain.entity.StockIndicator;
import com.example.demo.domain.entity.StockPrice;
import com.example.demo.domain.repository.CompanyRepository;
import com.example.demo.domain.repository.DividendInfoRepository;
import com.example.demo.domain.repository.FinancialStatementRepository;
import com.example.demo.domain.repository.StockIndicatorRepository;
import com.example.demo.domain.repository.StockPriceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FinancialIndicatorService {

    private final CompanyRepository companyRepository;
    private final FinancialStatementRepository financialStatementRepository;
    private final StockPriceRepository stockPriceRepository;
    private final StockIndicatorRepository stockIndicatorRepository;
    private final DividendInfoRepository dividendInfoRepository;
    private final ExchangeRateApiService exchangeRateApiService;

    // ── 상수 ──────────────────────────────────────────────────────────
    private static final int PAGE_SIZE = 100;

    // 재무제표 구분: 연결(CFS) 우선, 없으면 별도(OFS) 사용
    private static final String FS_DIV_CFS = "CFS"; // 연결재무제표
    private static final String FS_DIV_OFS = "OFS"; // 별도재무제표

    // ── 진입점 ────────────────────────────────────────────────────────

    /**
     * 전체 기업 지표 계산 및 저장
     *
     * @param year      기준 연도 (ex. "2023")
     * @param reprtCode 보고서 코드 (ex. "11011" = 사업보고서)
     */
    @Transactional
    public void calculateAll(String year, String reprtCode) {

        // 모멘텀/F-Score 기준일 = 어제 (당일 주가는 장 마감 전이라 아직 없을 수 있음)
        LocalDate baseDt = LocalDate.now().minusDays(1);

        int savedCount = 0;
        int page = 0;

        while (true) {

            // 100건씩 페이징 조회
            Pageable pageable = PageRequest.of(page, PAGE_SIZE);
            Page<Company> companyPage = companyRepository.findAll(pageable);
            List<Company> companies = companyPage.getContent();

            if (companies.isEmpty()) break;

            for (Company company : companies) {
                try {
                    boolean saved = calculateAndSave(company, year, reprtCode, baseDt);
                    if (saved) {
                        savedCount++;
                        log.info("지표 저장 완료: {} (page={}, total={})",
                                company.getCorpName(), page, savedCount);
                    }
                } catch (Exception e) {
                    // 개별 실패는 로그만 남기고 다음 기업 계속 처리
                    log.error("지표 계산 실패: {} | 사유: {}", company.getCorpName(), e.getMessage());
                }
            }

            if (!companyPage.hasNext()) break;
            page++;
        }

        log.info("지표 계산 완료: 총 {}건 저장", savedCount);
    }

    // ── 단일 기업 처리 ────────────────────────────────────────────────

    /**
     * 단일 기업 지표 계산 후 저장
     * 저장 성공 시 true, 데이터 부족으로 스킵 시 false 반환
     */
    private boolean calculateAndSave(Company company, String year, String reprtCode, LocalDate baseDt) {

        // ── 재무제표 조회: CFS 우선, 없으면 OFS ──────────────────────
        Optional<FinancialStatement> financialOpt =
                financialStatementRepository.findByStockCodeAndYearAndReprtCodeAndFsDiv(
                        company.getStockCode(), year, reprtCode, FS_DIV_CFS);

        if (financialOpt.isEmpty()) {
            financialOpt = financialStatementRepository.findByStockCodeAndYearAndReprtCodeAndFsDiv(
                    company.getStockCode(), year, reprtCode, FS_DIV_OFS);
        }

        if (financialOpt.isEmpty()) {
            log.warn("재무데이터 없음 (CFS/OFS 모두): {} year={} reprtCode={}",
                    company.getCorpName(), year, reprtCode);
            return false;
        }

        // ── 최신 주가 조회 ────────────────────────────────────────────
        Optional<StockPrice> stockPriceOpt =
                stockPriceRepository.findTopBySrtnCdOrderByBasDtDesc(company.getStockCode());

        if (stockPriceOpt.isEmpty()) {
            log.warn("주가데이터 없음: {}", company.getCorpName());
            return false;
        }

        FinancialStatement financial = financialOpt.get();
        StockPrice stockPrice = stockPriceOpt.get();

        // ── 지표 계산 후 저장 (upsert: PK 충돌 시 덮어쓰기) ─────────
        StockIndicator indicator = calculate(company, financial, stockPrice, year, baseDt);
        stockIndicatorRepository.save(indicator);

        return true;
    }

    // ── 지표 계산 ─────────────────────────────────────────────────────

    /**
     * EPS, BPS, PER, PBR, ROE, 부채비율, 배당수익률 계산
     * 환율 조회 실패 시 IllegalStateException → calculateAll에서 catch 후 스킵
     */
    private StockIndicator calculate(Company company, FinancialStatement financial,
                                     StockPrice stockPrice, String year, LocalDate baseDt) {

        // ── 환율 조회 ─────────────────────────────────────────────────
        // currency가 null이면 KRW로 간주 → fxRate = 1.0
        String currency = financial.getCurrency() != null ? financial.getCurrency() : "KRW";
        double fxRate = resolveExchangeRate(company.getCorpName(), currency);

        // ── 기초 데이터 추출 ──────────────────────────────────────────
        long shareCount  = nvl(stockPrice.getLstgStCnt());          // 상장주식수
        long closePrice  = nvl(stockPrice.getClpr());               // 종가 (항상 KRW)

        // 재무 수치 → KRW 변환 (KRW 통화면 fxRate = 1.0이라 값 동일)
        long netIncome   = toKrw(financial.getNetIncome(),         fxRate); // 당기순이익
        long equity      = toKrw(financial.getTotalEquity(),       fxRate); // 자본총계
        long liabilities = toKrw(financial.getTotalLiabilities(), fxRate); // 부채총계

        // ── 지표 계산 ─────────────────────────────────────────────────

        // EPS (주당순이익) = 당기순이익 / 상장주식수
        Double eps = safeDiv(netIncome, shareCount);

        // 완전자본잠식 (equity <= 0): BPS·PBR·ROE·부채비율 N/A
        // → 분모가 0 이하이면 수치가 폭발하거나 의미가 없어짐
        Double bps       = equity > 0 ? safeDiv(equity, shareCount)                      : null;
        Double pbr       = equity > 0 && bps != null ? round(closePrice / bps)           : null;
        Double roe       = equity > 0 ? round((double) netIncome / equity * 100)         : null;
        Double debtRatio = equity > 0 ? round((double) liabilities / equity * 100)       : null;

        // 적자 기업 (eps <= 0): PER N/A
        // → 음수 EPS로 계산된 PER은 실무에서 N/A 처리 (네이버 증권 등 동일)
        Double per = (eps != null && eps > 0) ? round(closePrice / eps) : null;

        // 배당수익률 = 주당배당금 / 종가 × 100
        // year 기준 보통주 배당 조회 (연도 불일치 방지)
        Double dividendYield = resolveDividendYield(company.getCorpCode(), closePrice);

        // ROA(총자산순이익률) = 당기순이익 / 자산총계 × 100
        // 같은 재무제표 내 두 항목의 비율이라 환율 변환 불필요 (원화 환산 전 금액이어도 비율은 동일)
        Long totalAssets = financial.getTotalAssets();
        Double roa = totalAssets != null && totalAssets > 0
                ? round((double) financial.getNetIncome() / totalAssets * 100)
                : null;

        // 모멘텀 계산에 쓰이는 1개월전·12개월전 종가 스냅샷 (F-Score의 신주발행 여부 판단에도 재사용)
        // baseDt(어제) 기준 - 당일 주가는 장 마감 전이라 아직 없을 수 있어서 어제를 기준일로 사용
        Optional<StockPrice> oneMonthAgoOpt = stockPriceRepository
                .findTopBySrtnCdAndBasDtLessThanEqualOrderByBasDtDesc(company.getStockCode(), baseDt.minusMonths(1));
        Optional<StockPrice> twelveMonthsAgoOpt = stockPriceRepository
                .findTopBySrtnCdAndBasDtLessThanEqualOrderByBasDtDesc(company.getStockCode(), baseDt.minusMonths(12));

        // 모멘텀 = (1개월전 종가 - 12개월전 종가) / 12개월전 종가 (12-1 모멘텀: 최근 1개월은 단기반전효과 배제)
        Double momentum = resolveMomentum(oneMonthAgoOpt, twelveMonthsAgoOpt);

        // Piotroski F-Score 계산 - 전년도 재무제표(같은 fsDiv) 있어야 계산 가능
        Optional<FinancialStatement> prevFinancialOpt = financialStatementRepository
                .findByStockCodeAndYearAndReprtCodeAndFsDiv(
                        company.getStockCode(), String.valueOf(Integer.parseInt(year) - 1),
                        financial.getReprtCode(), financial.getFsDiv());

        // 금융업(induty_code 64/65/66)은 유동비율·매출총이익률 등 F-Score 항목 구조 자체가 안 맞아 계산 스킵(null)
        // Top100Service의 F-Score 필터에서는 null을 "미달"이 아니라 "필터 예외(통과)"로 처리
        Integer fScore = company.isFinancialIndustry() ? null
                : resolveFScore(financial, prevFinancialOpt.orElse(null), roa, stockPrice, twelveMonthsAgoOpt.orElse(null));

        // EPS성장률 = (당기 EPS - 전기 EPS) / |전기 EPS| × 100. 전기 EPS = 전년도 당기순이익 / 1년전 상장주식수
        Double epsGrowthRate = resolveEpsGrowthRate(eps, prevFinancialOpt.orElse(null), twelveMonthsAgoOpt.orElse(null));

        // ── StockIndicator 빌드 ───────────────────────────────────────
        return StockIndicator.builder()
                .stockCode(company.getStockCode())
                .eps(eps != null ? round(eps) : null)
                .bps(bps != null ? round(bps) : null)
                .per(per)
                .pbr(pbr)
                .roe(roe)
                .debtRatio(debtRatio)
                .dividendYield(dividendYield)
                .roa(roa)
                .momentum(momentum)
                .fScore(fScore)
                .epsGrowthRate(epsGrowthRate)
                .calculatedAt(LocalDateTime.now())
                .build();
    }

    // 모멘텀 = (1개월전 종가 - 12개월전 종가) / 12개월전 종가. 기준일 데이터 없으면 계산 불가(null)
    private Double resolveMomentum(Optional<StockPrice> oneMonthAgoOpt, Optional<StockPrice> twelveMonthsAgoOpt) {
        if (oneMonthAgoOpt.isEmpty() || twelveMonthsAgoOpt.isEmpty()) return null;

        long oneMonthAgoPrice = nvl(oneMonthAgoOpt.get().getClpr());
        long twelveMonthsAgoPrice = nvl(twelveMonthsAgoOpt.get().getClpr());
        if (twelveMonthsAgoPrice == 0) return null;

        return round((double) (oneMonthAgoPrice - twelveMonthsAgoPrice) / twelveMonthsAgoPrice);
    }

    /**
     * Piotroski F-Score(0~9) 계산. 전년도 재무제표가 없으면 비교 자체가 불가능하므로 null 반환
     * - 수익성(4): ROA>0 / 영업현금흐름>0 / ROA 전년대비 증가 / 영업현금흐름>순이익
     * - 재무건전성(3): 부채비율 감소 / 유동비율 증가 / 신주발행 없음(상장주식수 비증가)
     * - 운영효율성(2): 매출총이익률 증가 / 자산회전율 증가
     */
    private Integer resolveFScore(FinancialStatement curr, FinancialStatement prev, Double currentRoa,
                                   StockPrice currentPrice, StockPrice yearAgoPrice) {
        if (prev == null) return null;

        int score = 0;

        // ── 수익성 ────────────────────────────────────────────────────
        if (currentRoa != null && currentRoa > 0) score++;

        Long cfo = curr.getOperatingCashFlow(); // 영업활동현금흐름
        if (cfo != null && cfo > 0) score++;

        Double prevRoa = prev.getTotalAssets() != null && prev.getTotalAssets() > 0
                ? (double) prev.getNetIncome() / prev.getTotalAssets() * 100 : null;
        if (currentRoa != null && prevRoa != null && currentRoa > prevRoa) score++;

        if (cfo != null && curr.getNetIncome() != null && cfo > curr.getNetIncome()) score++;

        // ── 재무건전성 ────────────────────────────────────────────────
        Double currDebtRatio = ratio(curr.getTotalLiabilities(), curr.getTotalEquity());
        Double prevDebtRatio = ratio(prev.getTotalLiabilities(), prev.getTotalEquity());
        if (currDebtRatio != null && prevDebtRatio != null && currDebtRatio < prevDebtRatio) score++;

        Double currCurrentRatio = ratio(curr.getCurrentAssets(), curr.getCurrentLiabilities());
        Double prevCurrentRatio = ratio(prev.getCurrentAssets(), prev.getCurrentLiabilities());
        if (currCurrentRatio != null && prevCurrentRatio != null && currCurrentRatio > prevCurrentRatio) score++;

        // 신주발행 없음 = 12개월전 대비 상장주식수가 늘지 않음
        if (currentPrice != null && yearAgoPrice != null
                && currentPrice.getLstgStCnt() != null && yearAgoPrice.getLstgStCnt() != null
                && currentPrice.getLstgStCnt() <= yearAgoPrice.getLstgStCnt()) score++;

        // ── 운영효율성 ────────────────────────────────────────────────
        Double currGrossMargin = ratio(curr.getGrossProfit(), curr.getRevenue());
        Double prevGrossMargin = ratio(prev.getGrossProfit(), prev.getRevenue());
        if (currGrossMargin != null && prevGrossMargin != null && currGrossMargin > prevGrossMargin) score++;

        Double currAssetTurnover = ratio(curr.getRevenue(), curr.getTotalAssets());
        Double prevAssetTurnover = ratio(prev.getRevenue(), prev.getTotalAssets());
        if (currAssetTurnover != null && prevAssetTurnover != null && currAssetTurnover > prevAssetTurnover) score++;

        return score;
    }

    // 분모가 null/0 이하면 계산 불가(null). 같은 재무제표 내 비율이라 환율 변환 불필요
    private Double ratio(Long numerator, Long denominator) {
        if (numerator == null || denominator == null || denominator <= 0) return null;
        return (double) numerator / denominator;
    }

    // EPS성장률 = (당기 EPS - 전기 EPS) / |전기 EPS| × 100
    // 전기 EPS = 전년도 당기순이익 / 1년전 상장주식수 (전년도 재무제표·1년전 주가 둘 다 있어야 계산 가능)
    private Double resolveEpsGrowthRate(Double eps, FinancialStatement prevFinancial, StockPrice yearAgoPrice) {
        if (eps == null || prevFinancial == null || yearAgoPrice == null) return null;

        Long prevNetIncome = prevFinancial.getNetIncome();
        Long prevShareCount = yearAgoPrice.getLstgStCnt();
        if (prevNetIncome == null || prevShareCount == null || prevShareCount == 0) return null;

        double prevEps = (double) prevNetIncome / prevShareCount;
        // 전기가 적자(prevEps<=0)면 성장률 %가 수학적으로 정의되지 않아(부호 전환) 계산 스킵
        // → null은 다른 팩터(ROE·ROA·모멘텀)와 동일하게 Top100Service에서 최하위로 처리됨
        if (prevEps <= 0) return null;

        return round((eps - prevEps) / prevEps * 100);
    }

    // DART 공시 오류로 "주당 현금배당금(원)"에 총액이 잘못 기재되는 경우가 있어(예: 와이엔텍),
    // 계산된 배당수익률이 비정상적으로 크면(100% 초과) 오류로 간주하고 null 처리
    private static final double MAX_VALID_DIVIDEND_YIELD = 100.0;

    private Double resolveDividendYield(String corpCode, long closePrice) {
        if (closePrice == 0) return null;

        Optional<DividendInfo> dividendOpt =
                dividendInfoRepository.findByCorpCodeAndDividendKind(corpCode, "보통주");

        return dividendOpt
                .filter(d -> d.getDividendAmount() != null)
                .map(d -> round((double) d.getDividendAmount() / closePrice * 100))
                .filter(yield -> yield <= MAX_VALID_DIVIDEND_YIELD)
                .orElse(null);
    }

    // ── 환율 조회 ─────────────────────────────────────────────────────

    /**
     * 환율 조회 실패 시 계산 스킵 (잘못된 환율로 저장하는 것보다 안전)
     * 호출부(calculateAndSave)에서 catch 후 해당 기업 건너뜀
     */
    private double resolveExchangeRate(String corpName, String currency) {
        try {
            return exchangeRateApiService.getRateToKrw(currency);
        } catch (Exception e) {
            log.warn("환율 조회 실패 → 지표 계산 스킵: {} currency={}", corpName, currency);
            throw new IllegalStateException("환율 조회 실패: " + currency, e);
        }
    }

    // ── 유틸 메서드 ───────────────────────────────────────────────────

    /** 외화 금액 → KRW 변환. null은 0 처리 */
    private long toKrw(Long amount, double fxRate) {
        if (amount == null) return 0L;
        return Math.round(amount * fxRate);
    }

    /** null → 0L 변환 (Long wrapper 안전 처리) */
    private long nvl(Long value) {
        return value != null ? value : 0L;
    }

    /** 0 나누기 방지. denominator가 0이면 null 반환 */
    private Double safeDiv(long numerator, long denominator) {
        if (denominator == 0) return null;
        return (double) numerator / denominator;
    }

    /** 소수점 둘째 자리 반올림 */
    private Double round(Double value) {
        if (value == null) return null;
        return Math.round(value * 100.0) / 100.0;
    }
}