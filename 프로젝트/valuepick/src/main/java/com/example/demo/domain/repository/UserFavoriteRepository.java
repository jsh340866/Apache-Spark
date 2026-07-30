package com.example.demo.domain.repository;

import com.example.demo.domain.entity.UserFavorite;
import com.example.demo.domain.entity.UserFavoriteId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface UserFavoriteRepository extends JpaRepository<UserFavorite, UserFavoriteId> {

    boolean existsByUserIdAndStockCode(Long userId, String stockCode);

    @Transactional
    void deleteByUserIdAndStockCode(Long userId, String stockCode);

    // 관심종목 목록 - 종목명/최신시세/지표를 함께 조회 (list.js의 normalizeStock과 동일한 컬럼 구조)
    @Query(value = """
            SELECT c.stock_code, c.corp_name,
                   i.per, i.roe, i.pbr, i.dividend_yield,
                   sp.mkp, sp.flt_rt, sp.mrkt_tot_amt
            FROM USER_FAVORITE f
            JOIN COMPANY c ON f.stock_code = c.stock_code
            LEFT JOIN STOCK_INDICATOR i ON c.stock_code = i.stock_code
            LEFT JOIN STOCK_PRICE sp ON c.stock_code = sp.srtn_cd
                AND sp.bas_dt = (
                    SELECT MAX(sp2.bas_dt)
                    FROM STOCK_PRICE sp2
                    WHERE sp2.srtn_cd = c.stock_code
                )
            WHERE f.user_id = :userId
            ORDER BY f.created_at DESC
            """,
            nativeQuery = true)
    List<Object> findFavoriteStocksByUserId(@Param("userId") Long userId);
}
