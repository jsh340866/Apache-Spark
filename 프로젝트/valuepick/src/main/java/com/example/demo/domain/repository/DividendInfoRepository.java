package com.example.demo.domain.repository;

import com.example.demo.domain.entity.DividendInfo;
import com.example.demo.domain.entity.DividendInfoId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DividendInfoRepository extends JpaRepository<DividendInfo, DividendInfoId> {

    Optional<DividendInfo> findByCorpCodeAndDividendKind(String corpCode, String dividendKind);

}
