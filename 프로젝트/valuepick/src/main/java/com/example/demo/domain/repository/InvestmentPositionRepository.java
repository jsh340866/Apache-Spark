package com.example.demo.domain.repository;

import com.example.demo.domain.entity.InvestmentPosition;
import com.example.demo.domain.entity.JournalState;
import com.example.demo.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InvestmentPositionRepository extends JpaRepository<InvestmentPosition, Long> {

    List<InvestmentPosition> findByUserOrderByCreatedAtDesc(User user);

    List<InvestmentPosition> findByUserAndStateOrderByCreatedAtDesc(User user, JournalState state);

    boolean existsByUserAndStockCodeAndState(User user, String stockCode, JournalState state);

    boolean existsByUserAndCorpNameAndState(User user, String corpName, JournalState state);
}
