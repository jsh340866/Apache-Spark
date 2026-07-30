package com.example.demo.domain.dto;

import com.example.demo.domain.entity.StockPrice;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockPriceDto {

    private String srtnCd;
    private LocalDate basDt;
    private Long clpr;
    private Long lstgStCnt;
    private Long mrktTotAmt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long mkp;
    private Double fltRt;

    public StockPrice toEntity() {
        return StockPrice.builder()
                .srtnCd(this.srtnCd)
                .basDt(this.basDt)
                .clpr(this.clpr)
                .lstgStCnt(this.lstgStCnt)
                .mrktTotAmt(this.mrktTotAmt)
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt)
                .mkp(this.mkp)
                .fltRt(this.fltRt)
                .build();
    }

    public static StockPriceDto from(StockPrice entity) {
        return StockPriceDto.builder()
                .srtnCd(entity.getSrtnCd())
                .basDt(entity.getBasDt())
                .clpr(entity.getClpr())
                .lstgStCnt(entity.getLstgStCnt())
                .mrktTotAmt(entity.getMrktTotAmt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .mkp(entity.getMkp())
                .fltRt(entity.getFltRt())
                .build();
    }
}
