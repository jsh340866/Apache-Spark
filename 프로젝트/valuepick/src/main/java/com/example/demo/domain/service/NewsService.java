package com.example.demo.domain.service;

import com.example.demo.domain.dto.NewsDto;
import com.example.demo.domain.repository.NewsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NewsService {

    private static final int PAGE_SIZE = 6;

    private final NewsRepository newsRepository;

    public Page<NewsDto> getNews(String stockCode, int page) {
        return newsRepository.findByStockCodeOrderByPublishedAtDesc(stockCode, PageRequest.of(page, PAGE_SIZE))
                .map(NewsDto::from);
    }
}
