package com.example.demo.domain.service;

import com.example.demo.domain.entity.StockIndicator;
import com.example.demo.domain.entity.Top100;
import com.example.demo.domain.repository.StockIndicatorRepository;
import com.example.demo.domain.repository.Top100Repository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class Top100Service {

    private final StockIndicatorRepository stockIndicatorRepository;
    private final Top100Repository top100Repository;

    // Piotroski F-Score 통과 기준 (9점 만점 중 6점 이상)
    private static final int F_SCORE_PASS_THRESHOLD = 6;

    // ── 진입점 ────────────────────────────────────────────────────────────────

    /**
     * STOCK_INDICATOR 전체 조회 → 스코어 계산 → 상위 100개 TOP100 테이블 저장
     * 같은 날짜 데이터가 이미 존재하면 재계산 없이 건너뜀
     */
    @Transactional
    public void calculateAndSave() {

        LocalDate baseDt = LocalDate.now().minusDays(1);

        if (top100Repository.existsByBaseDt(baseDt)) {
            log.info("[Top100Service] {}일자 데이터가 이미 존재합니다. 건너뜁니다.", baseDt);
            return;
        }

        // PER·PBR·ROE 모두 존재하는 종목만 스코어링 대상 (JOIN FETCH로 N+1 방지)
        List<StockIndicator> indicators = stockIndicatorRepository.findAllWithCompanyForScoring();

        if (indicators.isEmpty()) {
            log.warn("[Top100Service] 스코어링 가능한 지표 데이터가 없습니다.");
            return;
        }

        // 업종 미분류(induty_code 미수집) 종목은 금융업 여부를 판단할 수 없어 F-Score 필터를 공정하게 적용 못 함 → 후보에서 제외
        // (진짜 금융업인데 induty_code가 아직 없으면 예외 통과를 못 받고 구조적으로 부당하게 탈락하는 걸 방지)
        // F-Score 필터: 6점 이상만 통과. 단, 금융업은 F-Score 자체를 계산 안 해서 null이라 필터 예외로 통과시킴
        // corp_cls "Y" = 코스피(유가증권시장)만 대상. 코스닥("K") 등은 제외
        List<StockIndicator> candidates = indicators.stream()
                .filter(i -> "Y".equals(i.getCompany().getCorpCls()))
                .filter(i -> i.getCompany().getIndutyCode() != null)
                .filter(i -> i.getCompany().isFinancialIndustry()
                        || (i.getFScore() != null && i.getFScore() >= F_SCORE_PASS_THRESHOLD))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            log.warn("[Top100Service] F-Score 필터 통과 종목이 없습니다.");
            return;
        }

        log.info("[Top100Service] 스코어링 대상 종목 수: {} (F-Score 필터 통과: {})", indicators.size(), candidates.size());

        List<ScoredIndicator> scored = scoreAll(candidates);

        // 점수 내림차순 정렬 후 상위 100개 추출
        scored.sort(Comparator.comparingDouble(ScoredIndicator::score).reversed());
        List<ScoredIndicator> top100 = scored.stream().limit(100).collect(Collectors.toList());

        List<Top100> entities = top100.stream()
                .map(s -> Top100.builder()
                        .baseDt(baseDt)
                        .stockCode(s.indicator().getStockCode())
                        .corpCode(s.indicator().getCompany().getCorpCode())
                        .score(s.score())
                        .build())
                .collect(Collectors.toList());

        top100Repository.saveAll(entities);
        log.info("[Top100Service] TOP100 저장 완료: {}건 (baseDt={})", entities.size(), baseDt);
    }

    // ── 조회 ──────────────────────────────────────────────────────────────────

    /** 가장 최근 날짜 기준 점수 상위 10개 반환 */
    public List<Object> getTop10() {
        return top100Repository.findTop10OrderByScoreDesc();
    }

    /** 가장 최근 날짜 기준 전체 100개 슬라이스 페이징 */
    public Slice<Object> getTop100(int page) {
        return top100Repository.findTop100BySlice(PageRequest.of(page, 100));
    }

    // ── 스코어 계산 ───────────────────────────────────────────────────────────

    // 팩터별 가중치 (합계 1.0) - PER25%/PBR15%/ROE20%/ROA10%/부채비율15%/EPS성장률5%/모멘텀10%
    private static final double WEIGHT_PER = 0.25;
    private static final double WEIGHT_PBR = 0.15;
    private static final double WEIGHT_ROE = 0.20;
    private static final double WEIGHT_ROA = 0.10;
    private static final double WEIGHT_DEBT_RATIO = 0.15;
    private static final double WEIGHT_EPS_GROWTH = 0.05;
    private static final double WEIGHT_MOMENTUM = 0.10;

    /**
     * 각 지표를 백분위 기준 0~1로 정규화한 뒤 팩터별 가중치를 곱해 합산 (총점 0~1)
     * - PER·PBR·부채비율은 낮을수록 고점수 (저평가·재무건전성)
     * - ROE·ROA·EPS성장률·모멘텀은 높을수록 고점수 (수익성·성장성·추세)
     * - F-Score는 이 가중합산에 포함되지 않고 calculateAndSave()에서 사전 필터로만 사용
     */
    private List<ScoredIndicator> scoreAll(List<StockIndicator> indicators) {

        int n = indicators.size();

        // 각 지표값 추출: 낮을수록 좋은 지표 null → MAX_VALUE, 높을수록 좋은 지표 null → -MAX_VALUE
        double[] pers        = indicators.stream().mapToDouble(i -> { Double v = i.getPer();           return v != null ? v : Double.MAX_VALUE;  }).toArray();
        double[] pbrs        = indicators.stream().mapToDouble(i -> { Double v = i.getPbr();           return v != null ? v : Double.MAX_VALUE;  }).toArray();
        double[] roes        = indicators.stream().mapToDouble(i -> { Double v = i.getRoe();           return v != null ? v : -Double.MAX_VALUE; }).toArray();
        double[] roas        = indicators.stream().mapToDouble(i -> { Double v = i.getRoa();           return v != null ? v : -Double.MAX_VALUE; }).toArray();
        double[] debtRatios  = indicators.stream().mapToDouble(i -> { Double v = i.getDebtRatio();     return v != null ? v : Double.MAX_VALUE;  }).toArray();
        double[] epsGrowths  = indicators.stream().mapToDouble(i -> { Double v = i.getEpsGrowthRate(); return v != null ? v : -Double.MAX_VALUE; }).toArray();
        double[] momentums   = indicators.stream().mapToDouble(i -> { Double v = i.getMomentum();      return v != null ? v : -Double.MAX_VALUE; }).toArray();

        // 순위 배열 (0-based): 낮을수록 유리 → ascRank, 높을수록 유리 → descRank
        int[] perRank       = ascRank(pers);
        int[] pbrRank       = ascRank(pbrs);
        int[] roeRank       = descRank(roes);
        int[] roaRank       = descRank(roas);
        int[] debtRatioRank = ascRank(debtRatios);
        int[] epsGrowthRank = descRank(epsGrowths);
        int[] momentumRank  = descRank(momentums);

        List<ScoredIndicator> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double total = percentileFraction(perRank[i], n) * WEIGHT_PER
                    + percentileFraction(pbrRank[i], n) * WEIGHT_PBR
                    + percentileFraction(roeRank[i], n) * WEIGHT_ROE
                    + percentileFraction(roaRank[i], n) * WEIGHT_ROA
                    + percentileFraction(debtRatioRank[i], n) * WEIGHT_DEBT_RATIO
                    + percentileFraction(epsGrowthRank[i], n) * WEIGHT_EPS_GROWTH
                    + percentileFraction(momentumRank[i], n) * WEIGHT_MOMENTUM;

            result.add(new ScoredIndicator(indicators.get(i), total));
        }
        return result;
    }

    /**
     * 오름차순 순위 배열 반환 (가장 작은 값 → rank 0)
     * PER·PBR처럼 낮을수록 유리한 지표에 사용
     */
    private int[] ascRank(double[] values) {
        int n = values.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;

        java.util.Arrays.sort(idx, Comparator.comparingDouble(i -> values[i]));

        int[] rank = new int[n];
        for (int r = 0; r < n; r++) rank[idx[r]] = r;
        return rank;
    }

    /**
     * 내림차순 순위 배열 반환 (가장 큰 값 → rank 0)
     * ROE·배당수익률처럼 높을수록 유리한 지표에 사용
     */
    private int[] descRank(double[] values) {
        int n = values.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;

        idx = java.util.Arrays.stream(idx)
                .sorted((a, b) -> Double.compare(values[b], values[a]))
                .toArray(Integer[]::new);

        int[] rank = new int[n];
        for (int r = 0; r < n; r++) rank[idx[r]] = r;
        return rank;
    }

    /**
     * rank(0-based) → 0~1 백분위 값
     * rank=0(최고) → 1.0, rank=n-1(최저) → 0.0
     */
    private double percentileFraction(int rank, int n) {
        if (n <= 1) return 1.0;
        return (double) (n - 1 - rank) / (n - 1);
    }

    // ── 내부 레코드 ───────────────────────────────────────────────────────────

    private record ScoredIndicator(StockIndicator indicator, double score) {}
}