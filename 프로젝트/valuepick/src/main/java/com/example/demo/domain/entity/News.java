package com.example.demo.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "NEWS", uniqueConstraints = {
        @UniqueConstraint(name = "uk_news_stock_office_article",
                columnNames = {"stock_code", "office_id", "article_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class News {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false)
    private String stockCode;

    @Column(name = "office_id", nullable = false)
    private String officeId;

    @Column(name = "article_id", nullable = false)
    private String articleId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false)
    private String press;

    @Column(nullable = false, length = 500)
    private String link;

    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
