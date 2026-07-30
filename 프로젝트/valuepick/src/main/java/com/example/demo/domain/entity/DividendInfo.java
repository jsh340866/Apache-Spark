package com.example.demo.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "DIVIDEND_INFO")
@IdClass(DividendInfoId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class DividendInfo {

    @Id
    @Column(name = "corp_code")
    private String corpCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "corp_code", referencedColumnName = "corp_code", insertable = false, updatable = false)
    private Company company;

    @Id
    @Column(name = "dividend_kind")
    private String dividendKind;

    @Column(name = "dividend_amount")
    private Long dividendAmount;

    @Column(name = "stlm_dt")
    private LocalDateTime stlmDt;
}
