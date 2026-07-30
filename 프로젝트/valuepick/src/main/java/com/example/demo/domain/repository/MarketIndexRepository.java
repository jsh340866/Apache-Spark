package com.example.demo.domain.repository;

import com.example.demo.domain.entity.MarketIndex;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.time.LocalDate;

@Repository
public interface MarketIndexRepository extends JpaRepository<MarketIndex, Long> {
    // 데이터 삭제 메서드
    void deleteByBasDdBefore(LocalDate date);

    Optional<MarketIndex> findTop1ByIdxNmOrderByBasDdDesc(String idxNm);
}
