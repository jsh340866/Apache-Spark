package com.example.demo.domain.dto;

import com.example.demo.domain.entity.Top100;
import lombok.*;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Top100Dto {

    private LocalDate baseDt;
    private String stockCode;
    private String corpCode;
    private Double score;

    public Top100 toEntity() {
        return Top100.builder()
                .baseDt(this.baseDt)
                .stockCode(this.stockCode)
                .corpCode(this.corpCode)
                .score(this.score)
                .build();
    }

    public static Top100Dto from(Top100 entity) {
        return Top100Dto.builder()
                .baseDt(entity.getBaseDt())
                .stockCode(entity.getStockCode())
                .corpCode(entity.getCorpCode())
                .score(entity.getScore())
                .build();
    }
}
