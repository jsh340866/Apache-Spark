package com.example.demo.domain.dto;

import com.example.demo.domain.entity.UserFavorite;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserFavoriteDto {

    private Long userId;

    @NotBlank(message = "종목 코드는 필수 항목입니다.")
    private String stockCode;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public UserFavorite toEntity() {
        return UserFavorite.builder()
                .userId(this.userId)
                .stockCode(this.stockCode)
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt)
                .build();
    }

    public static UserFavoriteDto from(UserFavorite entity) {
        return UserFavoriteDto.builder()
                .userId(entity.getUserId())
                .stockCode(entity.getStockCode())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
