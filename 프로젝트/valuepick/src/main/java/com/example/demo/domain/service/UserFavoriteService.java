package com.example.demo.domain.service;

import com.example.demo.domain.entity.UserFavorite;
import com.example.demo.domain.repository.CompanyRepository;
import com.example.demo.domain.repository.UserFavoriteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserFavoriteService {

    private final UserFavoriteRepository userFavoriteRepository;
    private final CompanyRepository companyRepository;

    public List<Map<String, Object>> getFavorites(Long userId) {
        List<Object> rows = userFavoriteRepository.findFavoriteStocksByUserId(userId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : rows) {
            result.add(rowToMap((Object[]) row));
        }
        return result;
    }

    @Transactional
    public void addFavorite(Long userId, String stockCode) {
        if (!companyRepository.existsById(stockCode)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "존재하지 않는 종목코드입니다: " + stockCode);
        }
        if (userFavoriteRepository.existsByUserIdAndStockCode(userId, stockCode)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        userFavoriteRepository.save(UserFavorite.builder()
                .userId(userId)
                .stockCode(stockCode)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Transactional
    public void removeFavorite(Long userId, String stockCode) {
        userFavoriteRepository.deleteByUserIdAndStockCode(userId, stockCode);
    }

    private Map<String, Object> rowToMap(Object[] row) {
        Map<String, Object> m = new HashMap<>();
        m.put("stock_code",     row[0]);
        m.put("corp_name",      row[1]);
        m.put("per",            row[2]);
        m.put("roe",            row[3]);
        m.put("pbr",            row[4]);
        m.put("dividend_yield", row[5]);
        m.put("mkp",            row[6]);
        m.put("flt_rt",         row[7]);
        m.put("mrkt_tot_amt",   row[8]);
        return m;
    }
}
