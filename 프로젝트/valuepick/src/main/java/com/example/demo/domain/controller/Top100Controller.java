package com.example.demo.domain.controller;

import com.example.demo.domain.service.Top100Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Slice;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/top100")
public class Top100Controller {

    private final Top100Service top100Service;

    // 점수 상위 10개 종목 반환 (홈화면 위젯용)
    @GetMapping("/top10")
    public ResponseEntity<List<Object>> getTop10() {
        return ResponseEntity.ok(top100Service.getTop10());
    }

    // 전체 100개 슬라이스 페이징 (page=0부터 시작)
    @GetMapping
    public ResponseEntity<Slice<Object>> getTop100(@RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(top100Service.getTop100(page));
    }

    // 관리자용: 수동 스코어 재계산 트리거
    @PostMapping("/admin/calculate")
    public ResponseEntity<String> calculate() {
        log.info("[Top100Controller] 수동 TOP100 스코어 계산 요청");
        top100Service.calculateAndSave();
        return ResponseEntity.ok("TOP100 스코어 계산 완료");
    }
}