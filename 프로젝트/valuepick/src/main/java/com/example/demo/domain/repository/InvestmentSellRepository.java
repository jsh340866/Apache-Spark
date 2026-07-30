package com.example.demo.domain.repository;

import com.example.demo.domain.entity.InvestmentPosition;
import com.example.demo.domain.entity.InvestmentSell;
import com.example.demo.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface InvestmentSellRepository extends JpaRepository<InvestmentSell, Long> {

    List<InvestmentSell> findByUserOrderBySellAtDesc(User user);

    List<InvestmentSell> findByPosition(InvestmentPosition position);

    List<InvestmentSell> findByPositionOrderBySellAtDesc(InvestmentPosition position);

    void deleteByPosition(InvestmentPosition position);

    @Query("SELECT COALESCE(SUM(s.quantity), 0) FROM InvestmentSell s WHERE s.position.id = :positionId")
    int sumQuantityByPositionId(@Param("positionId") Long positionId);

    @Query("SELECT COALESCE(SUM(s.price * s.quantity), 0) FROM InvestmentSell s WHERE s.position.id = :positionId")
    long sumAmountByPositionId(@Param("positionId") Long positionId);

    @Query("SELECT MAX(s.sellAt) FROM InvestmentSell s WHERE s.position.id = :positionId")
    Optional<LocalDateTime> findLatestSellAt(@Param("positionId") Long positionId);

    int countByPosition(InvestmentPosition position);
}
