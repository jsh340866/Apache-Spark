package com.example.demo.domain.repository;

import com.example.demo.domain.entity.News;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NewsRepository extends JpaRepository<News, Long> {

    boolean existsByStockCodeAndOfficeIdAndArticleId(String stockCode, String officeId, String articleId);

    Page<News> findByStockCodeOrderByPublishedAtDesc(String stockCode, Pageable pageable);
}
