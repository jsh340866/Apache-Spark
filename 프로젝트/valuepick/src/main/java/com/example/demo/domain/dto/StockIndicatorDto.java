package com.example.demo.domain.dto;

import com.example.demo.domain.entity.StockIndicator;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockIndicatorDto {

    private String stockCode;
    private Double per;
    private Double pbr;
    private Double roe;
    private Double debtRatio;
    private Double dividendYield;
    private LocalDateTime calculatedAt;
    private Double eps;
    private Double bps;
    private Double roa;
    private Double momentum;
    private Integer fScore;
    private Double epsGrowthRate;

    public StockIndicator toEntity() {
        return StockIndicator.builder()
                .stockCode(this.stockCode)
                .per(this.per)
                .pbr(this.pbr)
                .roe(this.roe)
                .debtRatio(this.debtRatio)
                .dividendYield(this.dividendYield)
                .calculatedAt(this.calculatedAt)
                .eps(this.eps)
                .bps(this.bps)
                .roa(this.roa)
                .momentum(this.momentum)
                .fScore(this.fScore)
                .epsGrowthRate(this.epsGrowthRate)
                .build();
    }

    public static StockIndicatorDto from(StockIndicator entity) {
        return StockIndicatorDto.builder()
                .stockCode(entity.getStockCode())
                .per(entity.getPer())
                .pbr(entity.getPbr())
                .roe(entity.getRoe())
                .debtRatio(entity.getDebtRatio())
                .dividendYield(entity.getDividendYield())
                .calculatedAt(entity.getCalculatedAt())
                .eps(entity.getEps())
                .bps(entity.getBps())
                .roa(entity.getRoa())
                .momentum(entity.getMomentum())
                .fScore(entity.getFScore())
                .epsGrowthRate(entity.getEpsGrowthRate())
                .build();
    }
}
