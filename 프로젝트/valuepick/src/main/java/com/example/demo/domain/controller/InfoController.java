package com.example.demo.domain.controller;

import com.example.demo.domain.dto.ExchangeDto;
import com.example.demo.domain.dto.MarketIndexDto;
import com.example.demo.domain.service.SimpleInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/info")
public class InfoController {
    @Autowired
    SimpleInfoService simpleInfoService;

    // 저PER 상위 5
    @GetMapping(value = "/per",produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> getPER() throws Exception {
        return ResponseEntity.ok(simpleInfoService.getPER());
    }
    // 저PBR 상위 5
    @GetMapping(value = "/pbr",produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> getPBR() throws Exception {
        return ResponseEntity.ok(simpleInfoService.getPBR());
    }

    // 고ROE 상위 5
    @GetMapping(value = "/roe",produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> getROE() throws Exception {
        return ResponseEntity.ok(simpleInfoService.getROE());
    }

    // 고배당수익률 상위 5
    @GetMapping(value = "/dividend-yield",produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> getDividendYield() throws Exception {
        return ResponseEntity.ok(simpleInfoService.getDividendYield());
    }

    // 코스피
    @GetMapping(value = "/kospi",produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MarketIndexDto> getKOSPI() throws Exception {
        return ResponseEntity.ok(simpleInfoService.getKOSPI());
    }

    // 환율
    @GetMapping(value = "/exchange",produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String,Object>> getExchange() throws Exception {
        return ResponseEntity.ok(simpleInfoService.getExchange());
    }

    // TOP10
    @GetMapping(value = "/top10",produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getTOP10() throws Exception {
        return ResponseEntity.ok(simpleInfoService.getTOP10());
    }

    // TOP100 슬라이스 (무한스크롤)
    @GetMapping(value = "/top100",produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getTOP100(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) throws Exception {
        return ResponseEntity.ok(simpleInfoService.getTOP100(page, size));
    }

    // 전체 목록 (company + indicator + 최신 주가)
    @GetMapping(value = "/list",produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getList(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false)    String sort,
            @RequestParam(required = false)    String dir) throws Exception {
        return ResponseEntity.ok(simpleInfoService.getList(page, size, sort, dir));
    }

    // 필터 목록 (per, roe, pbr, dividendYield 최소/최대, 페이징)
    @GetMapping(value = "/list/filter",produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getListWithFilter(
            @RequestParam(required = false) Double perMin,
            @RequestParam(required = false) Double perMax,
            @RequestParam(required = false) Double roeMin,
            @RequestParam(required = false) Double roeMax,
            @RequestParam(required = false) Double pbrMin,
            @RequestParam(required = false) Double pbrMax,
            @RequestParam(required = false) Double dyMin,
            @RequestParam(required = false) Double dyMax,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false)    String sort,
            @RequestParam(required = false)    String dir) throws Exception {
        return ResponseEntity.ok(simpleInfoService.getListWithFilter(
                perMin, perMax, roeMin, roeMax, pbrMin, pbrMax, dyMin, dyMax, page, size, sort, dir));
    }

    // 기업명 검색 (페이징)
    @GetMapping(value = "/search",produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false)    String sort,
            @RequestParam(required = false)    String dir) throws Exception {
        return ResponseEntity.ok(simpleInfoService.getSerachResult(keyword, page, size, sort, dir));
    }

}
