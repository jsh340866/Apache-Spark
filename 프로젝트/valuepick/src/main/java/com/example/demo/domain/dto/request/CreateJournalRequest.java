package com.example.demo.domain.dto.request;

import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateJournalRequest {

    private String title;
    private String stockCode;
    private String corpName;
    private LocalDateTime buyAt;
    private Long price;
    private Integer quantity;
}
