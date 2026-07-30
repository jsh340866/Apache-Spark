package com.example.demo.domain.repository;

import com.example.demo.domain.entity.FinancialStatement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FinancialStatementRepository extends JpaRepository<FinancialStatement, Long> {

    // 기존: fsDiv 구분 없이 조회
    @Query("SELECT f FROM FinancialStatement f WHERE f.company.stockCode = :stockCode AND f.bsnsYear = :year AND f.reprtCode = :reprtCode")
    Optional<FinancialStatement> findByStockCodeAndYearAndReprtCode(
            String stockCode, String year, String reprtCode);

    // 추가: CFS/OFS 구분 포함 조회
    @Query("SELECT f FROM FinancialStatement f WHERE f.company.stockCode = :stockCode AND f.bsnsYear = :year AND f.reprtCode = :reprtCode AND f.fsDiv = :fsDiv")
    Optional<FinancialStatement> findByStockCodeAndYearAndReprtCodeAndFsDiv(
            String stockCode, String year, String reprtCode, String fsDiv);

    List<FinancialStatement> findByCompany_StockCodeOrderByBsnsYearDesc(String stockCode);
}