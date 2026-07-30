package com.example.demo.domain.dto;

import com.example.demo.domain.entity.InvestmentSell;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvestmentSellDto {

    private Long id;
    private Long positionId;
    private String stockCode;
    private String corpName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime sellAt;
    private Long price;
    private Integer quantity;
    private Boolean isShared;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    public static InvestmentSellDto from(InvestmentSell entity) {
        return InvestmentSellDto.builder()
                .id(entity.getId())
                .positionId(entity.getPosition().getId())
                .stockCode(entity.getStockCode())
                .corpName(entity.getCorpName())
                .sellAt(entity.getSellAt())
                .price(entity.getPrice())
                .quantity(entity.getQuantity())
                .isShared(entity.getIsShared())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
