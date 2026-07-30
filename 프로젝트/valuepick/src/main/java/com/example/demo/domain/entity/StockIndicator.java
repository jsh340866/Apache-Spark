package com.example.demo.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "STOCK_INDICATOR")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class StockIndicator {

    @Id
    @Column(name = "stock_code")
    private String stockCode;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_code", insertable = false, updatable = false)
    private Company company;

    private Double per;
    private Double pbr;
    private Double roe;

    @Column(name = "debt_ratio")
    private Double debtRatio;

    @Column(name = "dividend_yield")
    private Double dividendYield;

    @Column(name = "calculated_at")
    private LocalDateTime calculatedAt;

    private Double eps;
    private Double bps;
    private Double roa;
    private Double momentum;

    @Column(name = "f_score")
    private Integer fScore;

    @Column(name = "eps_growth_rate")
    private Double epsGrowthRate;
}
