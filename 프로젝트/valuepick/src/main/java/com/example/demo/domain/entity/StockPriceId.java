package com.example.demo.domain.entity;

import lombok.*;
import java.io.Serializable;
import java.time.LocalDate;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class StockPriceId implements Serializable {
    private String srtnCd;
    private LocalDate basDt;
}
