package com.example.demo.domain.dto.request;

import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddBuyRequest {

    private LocalDateTime buyAt;
    private Long price;
    private Integer quantity;
}
