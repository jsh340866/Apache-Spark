package com.example.demo.domain.dto;

import com.example.demo.domain.entity.Exchange;
import lombok.*;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExchangeDto {

    private String curUnit;
    private LocalDate baseDate;
    private String country;
    private Double dealBasR;
    private Double changeRate;
    private Double changeAmount;

    public Exchange toEntity() {
        return Exchange.builder()
                .curUnit(this.curUnit)
                .baseDate(this.baseDate)
                .country(this.country)
                .dealBasR(this.dealBasR)
                .changeRate(this.changeRate)
                .changeAmount(this.changeAmount)
                .build();
    }

    public static ExchangeDto from(Exchange entity) {
        return ExchangeDto.builder()
                .curUnit(entity.getCurUnit())
                .baseDate(entity.getBaseDate())
                .country(entity.getCountry())
                .dealBasR(entity.getDealBasR())
                .changeRate(entity.getChangeRate())
                .changeAmount(entity.getChangeAmount())
                .build();
    }
}
