package com.example.demo.domain.dto;

import com.example.demo.domain.entity.MarketIndex;
import lombok.*;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketIndexDto {

    private Long id;
    private LocalDate basDd;
    private String idxNm;
    private Double flucRt;
    private Double opnprcIdx;
    private Double clsprcIdx;
    private Double cmpprevddIdx;
    private Long mktcap;

    public MarketIndex toEntity() {
        return MarketIndex.builder()
                .id(this.id)
                .basDd(this.basDd)
                .idxNm(this.idxNm)
                .flucRt(this.flucRt)
                .opnprcIdx(this.opnprcIdx)
                .clsprcIdx(this.clsprcIdx)
                .cmpprevddIdx(this.cmpprevddIdx)
                .mktcap(this.mktcap)
                .build();
    }

    public static MarketIndexDto from(MarketIndex entity) {
        return MarketIndexDto.builder()
                .id(entity.getId())
                .basDd(entity.getBasDd())
                .idxNm(entity.getIdxNm())
                .flucRt(entity.getFlucRt())
                .opnprcIdx(entity.getOpnprcIdx())
                .clsprcIdx(entity.getClsprcIdx())
                .cmpprevddIdx(entity.getCmpprevddIdx())
                .mktcap(entity.getMktcap())
                .build();
    }
}
