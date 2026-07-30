package com.example.demo.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;

@Entity
@Table(name = "EXCHANGE")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Exchange {

    @Id
    @Column(name = "curUnit")
    private String curUnit;

    private LocalDate baseDate;
    private String country;
    private Double dealBasR;
    private Double changeRate;
    private Double changeAmount;
}
