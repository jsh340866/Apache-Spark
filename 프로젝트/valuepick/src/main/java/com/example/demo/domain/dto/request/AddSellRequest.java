package com.example.demo.domain.dto.request;

import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddSellRequest {

    private LocalDateTime sellAt;
    private Long price;
    private Integer quantity;
}
