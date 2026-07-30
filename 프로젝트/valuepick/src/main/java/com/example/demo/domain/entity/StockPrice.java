package com.example.demo.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "STOCK_PRICE")
@IdClass(StockPriceId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class StockPrice {

    @Id
    @Column(name = "srtn_cd")
    private String srtnCd;

    @Id
    @Column(name = "bas_dt")
    private LocalDate basDt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "srtn_cd", referencedColumnName = "stock_code", insertable = false, updatable = false)
    private Company company;

    private Long clpr;

    @Column(name = "lstg_st_cnt")
    private Long lstgStCnt;

    @Column(name = "mrkt_tot_amt")
    private Long mrktTotAmt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private Long mkp;

    @Column(name = "flt_rt")
    private Double fltRt;
}
