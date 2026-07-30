package com.example.demo.domain.service;

import com.example.demo.domain.dto.ExchangeDto;
import com.example.demo.domain.dto.MarketIndexDto;
import com.example.demo.domain.dto.naverPolling.Data;
import com.example.demo.domain.dto.naverPolling.Root;
import com.example.demo.domain.entity.Exchange;
import com.example.demo.domain.entity.MarketIndex;
import com.example.demo.domain.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SimpleInfoServiceImpl implements SimpleInfoService {
    private static final Logger log = LoggerFactory.getLogger(SimpleInfoServiceImpl.class);
    private String naver_api = "https://polling.finance.naver.com/api/realtime";

    @Autowired StockIndicatorRepository indicatorRepository;
    @Autowired MarketIndexRepository    marketIndexRepository;
    @Autowired ExchangeRepository       exchangeRepository;
    @Autowired Top100Repository         top100Repository;
    @Autowired CompanyRepository        companyRepository;
    @Autowired EntityManager            entityManager;

    // ── 공통 SQL 베이스 ───────────────────────────────────────────
    private static final String LIST_SELECT = """
            SELECT c.stock_code, c.corp_name,
                   i.per, i.roe, i.pbr, i.dividend_yield,
                   sp.mkp, sp.flt_rt, sp.mrkt_tot_amt
            FROM COMPANY c
            LEFT JOIN STOCK_INDICATOR i  ON c.stock_code = i.stock_code
            LEFT JOIN STOCK_PRICE     sp ON c.stock_code = sp.srtn_cd
                AND sp.bas_dt = (
                    SELECT MAX(sp2.bas_dt) FROM STOCK_PRICE sp2
                    WHERE sp2.srtn_cd = c.stock_code
                )
            """;
    // ── api 요청 ─────────────────────────────────────────────────
    private Data naver_api_get(String stock_code) throws JsonProcessingException {
        URI uri = UriComponentsBuilder
                .fromHttpUrl(naver_api)
                .queryParam(
                        "query",
                        "SERVICE_POPULAR_ITEM:"+stock_code
                )
                .build()
                .toUri();
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/149.0.0.0 Safari/537.36");
        headers.set("Referer",
                "https://finance.naver.com/");
        headers.setAccept(List.of(MediaType.ALL));

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response =
                restTemplate.exchange(
                        uri,
                        HttpMethod.GET,
                        entity,
                        String.class
                );
        ObjectMapper mapper = new ObjectMapper();

        Root root = mapper.readValue(
                response.getBody(),
                Root.class
        );

        return root.getResult().getAreas().get(0).getDatas().get(0);

    }

    // ── 정렬 헬퍼 ─────────────────────────────────────────────────
    private String toDbColumn(String key) {
        if (key == null || key.isBlank()) return null;
        return switch (key) {
            case "dividendYield" -> "dividend_yield";
            case "price"         -> "mkp";
            case "changeRate"    -> "flt_rt";
            case "marketCap"     -> "mrkt_tot_amt";
            default              -> key;
        };
    }

    private String orderClause(String sort, String dir) {
        String col = toDbColumn(sort);
        if (col == null) return "";
        // null은 정렬 방향과 무관하게 항상 마지막으로 (MySQL은 기본적으로 ASC에서 null을 맨 앞에 둠)
        return " ORDER BY (" + col + " IS NULL) ASC, " + col + " " + ("asc".equalsIgnoreCase(dir) ? "ASC" : "DESC");
    }

    // ── 실시간 현재가/등락률 (API 실패 시 DB 값으로 폴백) ─────────────
    private void putRealtimePrice(Map<String, Object> m, String stockCode, Object dbMkp, Object dbFltRt) {
        try {
            Data data = naver_api_get(stockCode);
            m.put("mkp",    data.getNv());
            m.put("flt_rt", (data.getRf().equals("4") || data.getRf().equals("5") ? (data.getCr()*-1) : data.getRf().equals("2")|| data.getRf().equals("1") ? data.getCr() : 0));
        } catch (Exception e) {
            log.warn("네이버 실시간 시세 조회 실패 - stock_code={}, DB 값으로 대체: {}", stockCode, e.toString());
            m.put("mkp",    dbMkp);
            m.put("flt_rt", dbFltRt);
        }
    }

    // ── 결과 행 → Map ──────────────────────────────────────────────
    private Map<String, Object> rowToMap(Object[] row) {
        Map<String, Object> m = new HashMap<>();
        m.put("stock_code",     row[0]);
        m.put("corp_name",      row[1]);
        m.put("per",            row[2]);
        m.put("roe",            row[3]);
        m.put("pbr",            row[4]);
        m.put("dividend_yield", row[5]);
        putRealtimePrice(m, row[0].toString(), row[6], row[7]);
        m.put("mrkt_tot_amt",   row[8]);
        return m;
    }

    private Map<String, Object> toPageResult(List<?> content, long total, int page, int size) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object o : content) list.add(rowToMap((Object[]) o));
        Map<String, Object> result = new HashMap<>();
        result.put("list",       list);
        result.put("totalCount", total);
        result.put("totalPages", size > 0 ? (int) Math.ceil((double) total / size) : 0);
        result.put("page",       page);
        return result;
    }

    // ── 랭킹 ──────────────────────────────────────────────────────
    @Override
    public List<Map<String, Object>> getPER() throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object o : indicatorRepository.lowerPer5()) {
            Object[] row = (Object[]) o;
            Map<String, Object> m = new HashMap<>();
            m.put("stock_code", row[0]); m.put("per", row[1]); m.put("corp_name", row[2]);
            list.add(m);
        }
        return list;
    }

    @Override
    public List<Map<String, Object>> getPBR() throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object o : indicatorRepository.lowerPbr5()) {
            Object[] row = (Object[]) o;
            Map<String, Object> m = new HashMap<>();
            m.put("stock_code", row[0]); m.put("pbr", row[1]); m.put("corp_name", row[2]);
            list.add(m);
        }
        return list;
    }

    @Override
    public List<Map<String, Object>> getROE() throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object o : indicatorRepository.higherRoe5()) {
            Object[] row = (Object[]) o;
            Map<String, Object> m = new HashMap<>();
            m.put("stock_code", row[0]); m.put("roe", row[1]); m.put("corp_name", row[2]);
            list.add(m);
        }
        return list;
    }

    @Override
    public List<Map<String, Object>> getDividendYield() throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object o : indicatorRepository.higherDY5()) {
            Object[] row = (Object[]) o;
            Map<String, Object> m = new HashMap<>();
            m.put("stock_code", row[0]); m.put("dividend_yield", row[1]); m.put("corp_name", row[2]);
            list.add(m);
        }
        return list;
    }

    // ── TOP10 / TOP100 ────────────────────────────────────────────
    @Override
    public Map<String, Object> getTOP10() throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object o : top100Repository.findTop10OrderByScoreDesc()) {
            Object[] row = (Object[]) o;
            Map<String, Object> m = new HashMap<>();
            m.put("stock_code", row[0]); m.put("corp_name", row[1]);
            m.put("per", row[2]);       m.put("roe", row[3]);
            m.put("pbr", row[4]);       m.put("dividend_yield", row[5]);
            putRealtimePrice(m, row[0].toString(), row[6], row[7]);
            m.put("mrkt_tot_amt", row[8]); m.put("score", row[9]);
            list.add(m);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        return result;
    }

    @Override
    public Map<String, Object> getTOP100(int page, int size) throws Exception {
        Slice<Object> slice = top100Repository.findTop100BySlice(PageRequest.of(page, size));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object o : slice.getContent()) {
            Object[] row = (Object[]) o;
            Map<String, Object> m = new HashMap<>();
            m.put("stock_code", row[0]); m.put("corp_name", row[1]);
            m.put("per", row[2]);       m.put("roe", row[3]);
            m.put("pbr", row[4]);       m.put("dividend_yield", row[5]);
            putRealtimePrice(m, row[0].toString(), row[6], row[7]);
            m.put("mrkt_tot_amt", row[8]); m.put("score", row[9]);
            list.add(m);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("list",    list);
        result.put("hasNext", slice.hasNext());
        result.put("page",    page);
        return result;
    }

    // ── 시장 지표 ─────────────────────────────────────────────────
    @Override
    public MarketIndexDto getKOSPI() throws Exception {
        MarketIndex kospi = marketIndexRepository
                .findTop1ByIdxNmOrderByBasDdDesc("코스피")
                .orElseThrow(() -> new Exception("KOSPI 데이터가 없습니다."));
        return MarketIndexDto.from(kospi);
    }

    @Override
    public Map<String,Object> getExchange() throws Exception {
        Map<String, Object> ex = new HashMap<>();
        Exchange exchange = exchangeRepository
                .findById("USD")
                .orElseThrow(() -> new Exception("환율 데이터가 없습니다."));
        ex.put("USD",ExchangeDto.from(exchange));
        exchange = exchangeRepository
                .findById("JPY(100)")
                .orElseThrow(() -> new Exception("환율 데이터가 없습니다."));
        ex.put("JPY(100)",ExchangeDto.from(exchange));
        exchange = exchangeRepository
                .findById("CNH")
                .orElseThrow(() -> new Exception("환율 데이터가 없습니다."));
        ex.put("CNH",ExchangeDto.from(exchange));

        return ex;
    }

    // ── 전체 목록 (EntityManager 직접 SQL — Pageable Sort 회피) ───
    @Override
    public Map<String, Object> getList(int page, int size, String sort, String dir) throws Exception {
        String countSql = """
                SELECT COUNT(*) FROM COMPANY c
                LEFT JOIN STOCK_INDICATOR i  ON c.stock_code = i.stock_code
                LEFT JOIN STOCK_PRICE     sp ON c.stock_code = sp.srtn_cd
                    AND sp.bas_dt = (
                        SELECT MAX(sp2.bas_dt) FROM STOCK_PRICE sp2
                        WHERE sp2.srtn_cd = c.stock_code
                    )
                """;
        List<?> rows = entityManager.createNativeQuery(LIST_SELECT + orderClause(sort, dir))
                .setFirstResult(page * size).setMaxResults(size).getResultList();
        long total = ((Number) entityManager.createNativeQuery(countSql).getSingleResult()).longValue();
        return toPageResult(rows, total, page, size);
    }

    // ── 필터 목록 ─────────────────────────────────────────────────
    @Override
    public Map<String, Object> getListWithFilter(Double perMin, Double perMax,
                                                  Double roeMin, Double roeMax,
                                                  Double pbrMin, Double pbrMax,
                                                  Double dyMin,  Double dyMax,
                                                  int page, int size, String sort, String dir) throws Exception {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        Map<String, Object> params = new HashMap<>();
        if (perMin != null) { where.append(" AND i.per >= :perMin"); params.put("perMin", perMin); }
        if (perMax != null) { where.append(" AND i.per <= :perMax"); params.put("perMax", perMax); }
        if (roeMin != null) { where.append(" AND i.roe >= :roeMin"); params.put("roeMin", roeMin); }
        if (roeMax != null) { where.append(" AND i.roe <= :roeMax"); params.put("roeMax", roeMax); }
        if (pbrMin != null) { where.append(" AND i.pbr >= :pbrMin"); params.put("pbrMin", pbrMin); }
        if (pbrMax != null) { where.append(" AND i.pbr <= :pbrMax"); params.put("pbrMax", pbrMax); }
        if (dyMin  != null) { where.append(" AND i.dividend_yield >= :dyMin"); params.put("dyMin", dyMin); }
        if (dyMax  != null) { where.append(" AND i.dividend_yield <= :dyMax"); params.put("dyMax", dyMax); }

        String baseSql = LIST_SELECT + where;
        String countSql = """
                SELECT COUNT(*) FROM COMPANY c
                LEFT JOIN STOCK_INDICATOR i  ON c.stock_code = i.stock_code
                LEFT JOIN STOCK_PRICE     sp ON c.stock_code = sp.srtn_cd
                    AND sp.bas_dt = (
                        SELECT MAX(sp2.bas_dt) FROM STOCK_PRICE sp2
                        WHERE sp2.srtn_cd = c.stock_code
                    )
                """ + where;

        Query dataQ  = entityManager.createNativeQuery(baseSql + orderClause(sort, dir));
        Query countQ = entityManager.createNativeQuery(countSql);
        params.forEach(dataQ::setParameter);
        params.forEach(countQ::setParameter);
        dataQ.setFirstResult(page * size);
        dataQ.setMaxResults(size);

        List<?> rows  = dataQ.getResultList();
        long    total = ((Number) countQ.getSingleResult()).longValue();
        return toPageResult(rows, total, page, size);
    }

    // ── 검색 ──────────────────────────────────────────────────────
    @Override
    public Map<String, Object> getSerachResult(String keyword, int page, int size, String sort, String dir) throws Exception {
        String kw      = "%" + keyword + "%";
        String dataSql = LIST_SELECT + " WHERE c.corp_name LIKE :kw" + orderClause(sort, dir);
        String cntSql  = "SELECT COUNT(*) FROM COMPANY c WHERE c.corp_name LIKE :kw";

        List<?> rows = entityManager.createNativeQuery(dataSql)
                .setParameter("kw", kw)
                .setFirstResult(page * size).setMaxResults(size).getResultList();
        long total = ((Number) entityManager.createNativeQuery(cntSql)
                .setParameter("kw", kw).getSingleResult()).longValue();

        List<Map<String, Object>> list = new ArrayList<>();
        for (Object o : rows) list.add(rowToMap((Object[]) o));
        Map<String, Object> result = new HashMap<>();
        result.put("result",     list);
        result.put("totalCount", total);
        result.put("totalPages", size > 0 ? (int) Math.ceil((double) total / size) : 0);
        result.put("page",       page);
        return result;
    }
}


