package com.example.demo.domain.repository;

import com.example.demo.domain.entity.StockIndicator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

// StockIndicator의 PK는 stock_code(String) - 기존 FinancialIndicator(Long PK)에서 변경됨
@Repository
public interface StockIndicatorRepository extends JpaRepository<StockIndicator, String> {

    // Top100 스코어 계산 시 company(corpCode) 를 한 번에 로드 — N+1 방지
    @Query("SELECT i FROM StockIndicator i JOIN FETCH i.company WHERE i.per IS NOT NULL AND i.pbr IS NOT NULL AND i.roe IS NOT NULL")
    List<StockIndicator> findAllWithCompanyForScoring();
    //지표 테이블 들어오는 것 보고 다시 수정요망
    @Query(value = """
            SELECT i.stock_code, i.per, c.corp_name
            FROM STOCK_INDICATOR i
            JOIN COMPANY c ON i.stock_code = c.stock_code
            WHERE i.per is not null
            ORDER BY i.per ASC
            LIMIT 5
            """,
            nativeQuery = true)
    List<Object> lowerPer5();

    @Query(value = """
            SELECT i.stock_code, i.pbr, c.corp_name
            FROM STOCK_INDICATOR i
            JOIN COMPANY c ON i.stock_code = c.stock_code
            WHERE i.pbr is not null
            ORDER BY i.pbr ASC
            LIMIT 5
            """,
            nativeQuery = true)
    List<Object> lowerPbr5();

    @Query(value = """
            SELECT i.stock_code, i.roe, c.corp_name
            FROM STOCK_INDICATOR i
            JOIN COMPANY c ON i.stock_code = c.stock_code
            ORDER BY i.roe DESC 
            LIMIT 5
            """,
            nativeQuery = true)
    List<Object> higherRoe5();

    @Query(value = """
            SELECT i.stock_code, i.dividend_yield, c.corp_name
            FROM STOCK_INDICATOR i
            JOIN COMPANY c ON i.stock_code = c.stock_code
            WHERE i.dividend_yield is not null
            ORDER BY i.dividend_yield DESC 
            LIMIT 5
            """,
            nativeQuery = true)
    List<Object> higherDY5();
}
