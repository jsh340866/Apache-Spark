package com.example.demo.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;

@Entity
@Table(name = "MARKET_INDEX")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class MarketIndex {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate basDd;
    private String idxNm;
    private Double flucRt;
    private Double opnprcIdx;
    private Double clsprcIdx;
    private Double cmpprevddIdx;
    private Long mktcap;
}
