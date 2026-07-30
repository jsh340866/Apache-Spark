package com.example.demo.domain.dto;

import com.example.demo.domain.entity.News;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewsDto {

    private String title;
    private String press;
    private String link;
    private LocalDateTime publishedAt;

    public static NewsDto from(News entity) {
        return NewsDto.builder()
                .title(entity.getTitle())
                .press(entity.getPress())
                .link(entity.getLink())
                .publishedAt(entity.getPublishedAt())
                .build();
    }
}
