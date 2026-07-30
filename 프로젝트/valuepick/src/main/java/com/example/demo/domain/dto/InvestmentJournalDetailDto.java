package com.example.demo.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvestmentJournalDetailDto {

    private Long id;
    private String title;
    private String stockCode;
    private String corpName;
    private String state;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime firstBuyAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime finalSellAt;
    private String note;
    private Boolean isShared;
    private Integer holdingQty;
    private Long usedAmount;
    private Long soldAmount;
    private Long pnl;
    private Long currentPrice;
    private List<InvestmentBuyDto> buys;
    private List<InvestmentSellDto> sells;
}
