package com.example.demo.domain.entity;

import lombok.*;
import java.io.Serializable;
import java.time.LocalDate;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class Top100Id implements Serializable {
    private LocalDate baseDt;
    private String stockCode;
}
