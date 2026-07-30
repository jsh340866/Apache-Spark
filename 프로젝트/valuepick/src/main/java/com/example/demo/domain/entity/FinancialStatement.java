package com.example.demo.domain.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "FINANCIAL_STATEMENT",
        uniqueConstraints = @UniqueConstraint(columnNames = {"bsns_year", "stock_code", "reprt_code", "fs_div"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class FinancialStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bsns_year", columnDefinition = "CHAR(4)") // ,unique = true // 복합 UK 충돌
    private String bsnsYear;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_code", columnDefinition = "CHAR(6)") // ,unique = true // 복합 UK 충돌
    private Company company;

    @Column(name = "reprt_code", columnDefinition = "CHAR(5)") // ,unique = true // 복합 UK 충돌
    private String reprtCode;

    @Column(name = "fs_div", columnDefinition = "CHAR(3)") // ,unique = true // 복합 UK 충돌
    private String fsDiv;

    private Long revenue;

    @Column(name = "operating_income")
    private Long operatingIncome;

    @Column(name = "net_income")
    private Long netIncome;

    @Column(name = "total_assets")
    private Long totalAssets;

    @Column(name = "total_liabilities")
    private Long totalLiabilities;

    @Column(name = "total_equity")
    private Long totalEquity;

    @Column(name = "current_assets")
    private Long currentAssets;

    @Column(name = "current_liabilities")
    private Long currentLiabilities;

    @Column(name = "operating_cash_flow")
    private Long operatingCashFlow;

    @Column(name = "gross_profit")
    private Long grossProfit;

    @Column(name = "currency", length = 10)
    private String currency;
}
