package com.example.demo.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;

@Entity
@Table(name = "TOP100")
@IdClass(Top100Id.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Top100 {

    @Id
    @Column(name = "base_dt")
    private LocalDate baseDt;

    @Id
    @Column(name = "stock_code")
    private String stockCode;

    @Column(name = "corp_code")
    private String corpCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_code", insertable = false, updatable = false)
    private Company company;

    private Double score;
}
