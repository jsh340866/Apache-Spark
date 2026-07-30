package com.example.demo.domain.dto;

import com.example.demo.domain.entity.DividendInfo;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DividendInfoDto {

    private String corpCode;
    private String dividendKind;
    private Long dividendAmount;
    private LocalDateTime stlmDt;

    public DividendInfo toEntity() {
        return DividendInfo.builder()
                .corpCode(this.corpCode)
                .dividendKind(this.dividendKind)
                .dividendAmount(this.dividendAmount)
                .stlmDt(this.stlmDt)
                .build();
    }

    public static DividendInfoDto from(DividendInfo entity) {
        return DividendInfoDto.builder()
                .corpCode(entity.getCorpCode())
                .dividendKind(entity.getDividendKind())
                .dividendAmount(entity.getDividendAmount())
                .stlmDt(entity.getStlmDt())
                .build();
    }
}
