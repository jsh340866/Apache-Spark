package com.example.demo.domain.repository;

import com.example.demo.domain.entity.Top100;
import com.example.demo.domain.entity.Top100Id;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface Top100Repository extends JpaRepository<Top100, Top100Id> {

    boolean existsByBaseDt(LocalDate baseDt);

    void deleteByBaseDtBefore(LocalDate cutoff);

    @Query(value = """
            SELECT t.stock_code, c.corp_name,
                   i.per, i.roe, i.pbr, i.dividend_yield,
                   sp.mkp, sp.flt_rt, sp.mrkt_tot_amt,
                   t.score
            FROM TOP100 t
            JOIN COMPANY c ON t.stock_code = c.stock_code
            LEFT JOIN STOCK_INDICATOR i ON t.stock_code = i.stock_code
            LEFT JOIN STOCK_PRICE sp ON t.stock_code = sp.srtn_cd
                AND sp.bas_dt = (
                    SELECT MAX(sp2.bas_dt)
                    FROM STOCK_PRICE sp2
                    WHERE sp2.srtn_cd = t.stock_code
                )
            WHERE t.base_dt >= (select MAX(base_dt) from TOP100)
            ORDER BY t.base_dt DESC, t.score DESC
            LIMIT 10
            """,
            nativeQuery = true)
    List<Object> findTop10OrderByScoreDesc();

    @Query(value = """
            SELECT t.stock_code, c.corp_name,
                   i.per, i.roe, i.pbr, i.dividend_yield,
                   sp.mkp, sp.flt_rt, sp.mrkt_tot_amt,
                   t.score
            FROM TOP100 t
            JOIN COMPANY c ON t.stock_code = c.stock_code
            LEFT JOIN STOCK_INDICATOR i ON t.stock_code = i.stock_code
            LEFT JOIN STOCK_PRICE sp ON t.stock_code = sp.srtn_cd
                AND sp.bas_dt = (
                    SELECT MAX(sp2.bas_dt)
                    FROM STOCK_PRICE sp2
                    WHERE sp2.srtn_cd = t.stock_code
                )
            WHERE t.base_dt >= (select MAX(base_dt) from TOP100)
            ORDER BY t.base_dt DESC, t.score DESC
            """,
            nativeQuery = true)
    Slice<Object> findTop100BySlice(Pageable pageable);
}
