package com.example.demo.domain.service;

import com.example.demo.domain.entity.Company;
import com.example.demo.domain.entity.News;
import com.example.demo.domain.repository.CompanyRepository;
import com.example.demo.domain.repository.NewsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsCrawlService {

    private static final String NEWS_LIST_URL = "https://finance.naver.com/item/news_news.naver";
    private static final DateTimeFormatter PUBLISHED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");

    private final NewsRepository newsRepository;
    private final CompanyRepository companyRepository;

    // 종목코드 하나에 대한 네이버 금융 종목뉴스를 크롤링하여 신규 기사만 저장합니다.
    @Transactional
    public int crawlAndSave(String stockCode) {
        Document doc;
        try {
            String url = UriComponentsBuilder.fromUriString(NEWS_LIST_URL)
                    .queryParam("code", stockCode)
                    .queryParam("page", 1)
                    .toUriString();

            doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0")
                    .referrer("https://finance.naver.com/item/news.naver?code=" + stockCode)
                    .timeout(5000)
                    .get();
        } catch (Exception e) {
            log.error("{} 종목 뉴스 페이지 요청 실패: {}", stockCode, e.getMessage());
            return 0;
        }

        int savedCount = 0;
        for (Element titleLink : doc.select("a.tit")) {
            News news = parseNewsRow(stockCode, titleLink);
            if (news == null) {
                continue;
            }
            if (newsRepository.existsByStockCodeAndOfficeIdAndArticleId(
                    news.getStockCode(), news.getOfficeId(), news.getArticleId())) {
                continue;
            }
            newsRepository.save(news);
            savedCount++;
        }
        return savedCount;
    }

    // 전체 종목을 순회하며 뉴스를 수집합니다.
    public void crawlAndSaveAll() {
        List<Company> companies = companyRepository.findAll();
        for (Company company : companies) {
            try {
                int saved = crawlAndSave(company.getStockCode());
                log.info("{}({}) 뉴스 {}건 저장", company.getCorpName(), company.getStockCode(), saved);
                Thread.sleep(300);
            } catch (Exception e) {
                log.error("{} 뉴스 크롤링 중 오류 발생: {}", company.getStockCode(), e.getMessage());
            }
        }
    }

    // a.tit(제목/링크) 엘리먼트가 속한 tr 행에서 언론사, 날짜를 함께 읽어 News로 변환합니다.
    private News parseNewsRow(String stockCode, Element titleLink) {
        if (titleLink.parent() == null || titleLink.parent().parent() == null) {
            return null;
        }
        Element row = titleLink.parent().parent(); // a.tit -> td.title -> tr

        Element infoCell = row.selectFirst("td.info");
        Element dateCell = row.selectFirst("td.date");
        if (infoCell == null || dateCell == null) {
            return null;
        }

        String absoluteHref = titleLink.absUrl("href");
        var queryParams = UriComponentsBuilder.fromUriString(absoluteHref).build().getQueryParams();
        String officeId = queryParams.getFirst("office_id");
        String articleId = queryParams.getFirst("article_id");
        if (officeId == null || articleId == null) {
            return null;
        }

        LocalDateTime publishedAt;
        try {
            publishedAt = LocalDateTime.parse(dateCell.text().trim(), PUBLISHED_AT_FORMAT);
        } catch (Exception e) {
            return null;
        }

        String link = "https://n.news.naver.com/mnews/article/%s/%s".formatted(officeId, articleId);

        return News.builder()
                .stockCode(stockCode)
                .officeId(officeId)
                .articleId(articleId)
                .title(titleLink.text().trim())
                .press(infoCell.text().trim())
                .link(link)
                .publishedAt(publishedAt)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
