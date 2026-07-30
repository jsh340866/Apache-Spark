package com.example.demo.domain.dto;

import com.example.demo.domain.entity.Company;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanyDto {

    private String stockCode;
    private String corpCode;
    private String corpName;
    private String corpCls;
    private String ceoNm;
    private String indutyNm;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Company toEntity() {
        return Company.builder()
                .stockCode(this.stockCode)
                .corpCode(this.corpCode)
                .corpName(this.corpName)
                .corpCls(this.corpCls)
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt)
                .build();
    }

    public static CompanyDto from(Company entity) {
        return CompanyDto.builder()
                .stockCode(entity.getStockCode())
                .corpCode(entity.getCorpCode())
                .corpName(entity.getCorpName())
                .corpCls(entity.getCorpCls())
                .ceoNm(entity.getCeoNm())
                .indutyNm(entity.getIndutyNm())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
