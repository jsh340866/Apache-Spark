package com.example.demo.domain.entity;

import lombok.*;
import java.io.Serializable;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class UserFavoriteId implements Serializable {
    private Long userId;
    private String stockCode;
}
