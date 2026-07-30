package com.example.demo.domain.repository;

import com.example.demo.domain.entity.Exchange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ExchangeRepository extends JpaRepository<Exchange, String> {

    // 기준일자로 환율 목록 조회 - 특정 날짜의 전체 환율 데이터 가져올 때 사용
    List<Exchange> findByBaseDate(LocalDate baseDate);

    // 데이터 삭제 매서드
    void deleteByBaseDateBefore(LocalDate date);
   
    List<Exchange> findAllByOrderByBaseDate();

    Optional<Exchange> findTopByCurUnitOrderByBaseDateDesc(String curUnit);

}
