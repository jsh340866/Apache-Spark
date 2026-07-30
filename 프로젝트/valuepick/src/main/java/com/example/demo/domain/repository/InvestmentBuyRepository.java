package com.example.demo.domain.repository;

import com.example.demo.domain.entity.InvestmentBuy;
import com.example.demo.domain.entity.InvestmentPosition;
import com.example.demo.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface InvestmentBuyRepository extends JpaRepository<InvestmentBuy, Long> {

    List<InvestmentBuy> findByUserOrderByBuyAtDesc(User user);

    List<InvestmentBuy> findByPosition(InvestmentPosition position);

    List<InvestmentBuy> findByPositionOrderByBuyAtAsc(InvestmentPosition position);

    void deleteByPosition(InvestmentPosition position);

    @Query("SELECT COALESCE(SUM(b.quantity), 0) FROM InvestmentBuy b WHERE b.position.id = :positionId")
    int sumQuantityByPositionId(@Param("positionId") Long positionId);

    @Query("SELECT COALESCE(SUM(b.price * b.quantity), 0) FROM InvestmentBuy b WHERE b.position.id = :positionId")
    long sumAmountByPositionId(@Param("positionId") Long positionId);

    @Query("SELECT MIN(b.buyAt) FROM InvestmentBuy b WHERE b.position.id = :positionId AND b.buyAt > :after")
    Optional<LocalDateTime> findEarliestBuyAtAfter(@Param("positionId") Long positionId, @Param("after") LocalDateTime after);

    int countByPosition(InvestmentPosition position);
}
