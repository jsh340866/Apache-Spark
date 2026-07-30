package com.example.demo.domain.repository;

import com.example.demo.domain.entity.StockPrice;
import com.example.demo.domain.entity.StockPriceId;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.time.LocalDate;
import java.util.Optional;

// PK가 복합키(StockPriceId)로 변경됨 - 기존 Long 단일 PK에서 (srtnCd + basDt) 복합키로 변경
@Repository
public interface StockPriceRepository extends JpaRepository<StockPrice, StockPriceId> {

    // 특정 종목의 가장 최신 주가 조회 - 지표 계산 시 현재 주가로 사용
    // 기존 findTopByStockCodeOrderByTradeDateDesc → srtnCd, basDt로 변경
    Optional<StockPrice> findTopBySrtnCdOrderByBasDtDesc(String srtnCd);

    // 지정일 이전 중 가장 최근 종가 조회 - 모멘텀 계산 시 1개월전/12개월전 기준일이 휴장일일 수 있어서 사용
    Optional<StockPrice> findTopBySrtnCdAndBasDtLessThanEqualOrderByBasDtDesc(String srtnCd, LocalDate basDt);

    // 7일 이전 데이터 삭제
    void deleteByBasDtBefore(LocalDate date);

    List<StockPrice> findBySrtnCdAndBasDtGreaterThanEqualOrderByBasDtAsc(String srtnCd, LocalDate basDt);
}
