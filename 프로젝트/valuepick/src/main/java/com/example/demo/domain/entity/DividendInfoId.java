package com.example.demo.domain.entity;

import lombok.*;
import java.io.Serializable;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class DividendInfoId implements Serializable {
    private String corpCode;
    private String dividendKind;
}
