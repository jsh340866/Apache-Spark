package com.example.demo.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JournalListItemDto {

    private String type;       // "buy" | "sell" | "position"
    private Long id;
    private Long journalId;
    private String title;
    private String stockCode;
    private String corpName;
    private Boolean isShared;

    // buy
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime buyAt;

    // sell
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime sellAt;

    // position
    private String state;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime firstBuyAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime finalSellAt;
    private String note;
    private Integer holdingQty;
    private Long usedAmount;
    private Long soldAmount;
    private Long pnl;
    private Long currentPrice;

    // buy/sell (개별 기록)
    private Long price;
    private Integer quantity;
}
