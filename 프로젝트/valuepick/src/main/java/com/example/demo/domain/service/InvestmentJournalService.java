package com.example.demo.domain.service;

import com.example.demo.domain.dto.*;
import com.example.demo.domain.dto.naverPolling.Data;
import com.example.demo.domain.dto.naverPolling.Root;
import com.example.demo.domain.dto.request.*;
import com.example.demo.domain.entity.*;
import com.example.demo.domain.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class InvestmentJournalService {

    private static final int PAGE_SIZE = 10;
    private static final String NAVER_REALTIME_API = "https://polling.finance.naver.com/api/realtime";

    private final UserRepository userRepository;
    private final InvestmentPositionRepository positionRepository;
    private final InvestmentBuyRepository buyRepository;
    private final InvestmentSellRepository sellRepository;
    private final StockPriceRepository stockPriceRepository;
    private final RestTemplate restTemplate;

    // ─── 목록 조회 ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public JournalPageResult getList(String email, String category, String q,
                                     LocalDate from, LocalDate to, int page) {
        User user = findUser(email);
        List<JournalListItemDto> items = new ArrayList<>();
        String lq = q != null ? q.toLowerCase() : "";
        Set<String> buyCategories = Set.of("buy", "all", "shared");
        Set<String> sellCategories = Set.of("sell", "all", "shared");

        if (buyCategories.contains(category)) {
            buyRepository.findByUserOrderByBuyAtDesc(user).stream()
                    .filter(b -> matchesSearch(b.getPosition().getTitle(), b.getCorpName(), lq))
                    .filter(b -> matchesDate(b.getBuyAt() != null ? b.getBuyAt().toLocalDate() : null, from, to))
                    .filter(b -> !"shared".equals(category) || Boolean.TRUE.equals(b.getIsShared()))
                    .map(this::toBuyItem)
                    .forEach(items::add);
        }

        if (sellCategories.contains(category)) {
            sellRepository.findByUserOrderBySellAtDesc(user).stream()
                    .filter(s -> matchesSearch(s.getPosition().getTitle(), s.getCorpName(), lq))
                    .filter(s -> matchesDate(s.getSellAt() != null ? s.getSellAt().toLocalDate() : null, from, to))
                    .filter(s -> !"shared".equals(category) || Boolean.TRUE.equals(s.getIsShared()))
                    .map(this::toSellItem)
                    .forEach(items::add);
        }

        if (!"buy".equals(category) && !"sell".equals(category)) {
            List<InvestmentPosition> positions;
            if ("hold".equals(category)) {
                positions = positionRepository.findByUserAndStateOrderByCreatedAtDesc(user, JournalState.보유);
            } else if ("deal".equals(category)) {
                positions = positionRepository.findByUserAndStateOrderByCreatedAtDesc(user, JournalState.완료);
            } else {
                positions = positionRepository.findByUserOrderByCreatedAtDesc(user);
            }

            positions.stream()
                    .filter(p -> matchesSearch(p.getTitle(), p.getCorpName(), lq))
                    .filter(p -> {
                        LocalDate filterDate = p.getState() == JournalState.보유
                                ? (p.getFirstBuyAt() != null ? p.getFirstBuyAt().toLocalDate() : null)
                                : (p.getFinalSellAt() != null ? p.getFinalSellAt().toLocalDate() : null);
                        return matchesDate(filterDate, from, to);
                    })
                    .filter(p -> !"shared".equals(category) || Boolean.TRUE.equals(p.getIsShared()))
                    .map(this::toPositionItem)
                    .forEach(items::add);
        }

        items.sort(Comparator.comparing(this::sortTime, Comparator.nullsFirst(Comparator.naturalOrder())).reversed());

        int total = items.size();
        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, total);
        List<JournalListItemDto> pageItems = start < total ? items.subList(start, end) : Collections.emptyList();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / PAGE_SIZE));

        return new JournalPageResult(new ArrayList<>(pageItems), page, totalPages, total);
    }

    // ─── 상세 조회 (position) ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public InvestmentJournalDetailDto getDetail(String email, Long positionId) {
        User user = findUser(email);
        InvestmentPosition position = findPosition(positionId);
        checkOwnership(position.getUser(), user);

        int totalBuyQty = buyRepository.sumQuantityByPositionId(positionId);
        int totalSellQty = sellRepository.sumQuantityByPositionId(positionId);
        long usedAmount = buyRepository.sumAmountByPositionId(positionId);
        long soldAmount = sellRepository.sumAmountByPositionId(positionId);

        Long currentPrice = resolveCurrentPrice(position.getStockCode());

        List<InvestmentBuyDto> buys = buyRepository.findByPositionOrderByBuyAtAsc(position).stream()
                .map(InvestmentBuyDto::from)
                .collect(Collectors.toList());

        List<InvestmentSellDto> sells = sellRepository.findByPositionOrderBySellAtDesc(position).stream()
                .map(InvestmentSellDto::from)
                .collect(Collectors.toList());

        return InvestmentJournalDetailDto.builder()
                .id(position.getId())
                .title(position.getTitle())
                .stockCode(position.getStockCode())
                .corpName(position.getCorpName())
                .state(position.getState().name())
                .firstBuyAt(position.getFirstBuyAt())
                .finalSellAt(position.getFinalSellAt())
                .note(position.getNote())
                .isShared(position.getIsShared())
                .holdingQty(totalBuyQty - totalSellQty)
                .usedAmount(usedAmount)
                .soldAmount(soldAmount)
                .pnl(soldAmount - usedAmount)
                .currentPrice(currentPrice)
                .buys(buys)
                .sells(sells)
                .build();
    }

    // ─── 종목 추가 ────────────────────────────────────────────────────────────────

    public void createJournal(String email, CreateJournalRequest req) {
        User user = findUser(email);

        String stockCode = req.getStockCode();
        String corpName = req.getCorpName();

        if (stockCode != null && !stockCode.isBlank()) {
            if (positionRepository.existsByUserAndStockCodeAndState(user, stockCode, JournalState.보유)) {
                throw new IllegalArgumentException("이미 보유 중인 종목입니다. 추가 매수는 보유 상세에서 진행해주세요.");
            }
        } else {
            if (positionRepository.existsByUserAndCorpNameAndState(user, corpName, JournalState.보유)) {
                throw new IllegalArgumentException("이미 보유 중인 종목입니다. 추가 매수는 보유 상세에서 진행해주세요.");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        InvestmentPosition position = InvestmentPosition.builder()
                .user(user)
                .title(req.getTitle())
                .stockCode(stockCode)
                .corpName(corpName)
                .firstBuyAt(req.getBuyAt())
                .finalSellAt(null)
                .note("")
                .isShared(false)
                .state(JournalState.보유)
                .createdAt(now)
                .build();
        positionRepository.save(position);

        InvestmentBuy buy = InvestmentBuy.builder()
                .user(user)
                .position(position)
                .stockCode(stockCode)
                .corpName(corpName)
                .buyAt(req.getBuyAt())
                .price(req.getPrice())
                .quantity(req.getQuantity())
                .isShared(false)
                .createdAt(now)
                .build();
        buyRepository.save(buy);
    }

    // ─── 추가 매수 ────────────────────────────────────────────────────────────────

    public void addBuy(String email, Long positionId, AddBuyRequest req) {
        User user = findUser(email);
        InvestmentPosition position = findPosition(positionId);
        checkOwnership(position.getUser(), user);

        InvestmentBuy buy = InvestmentBuy.builder()
                .user(user)
                .position(position)
                .stockCode(position.getStockCode())
                .corpName(position.getCorpName())
                .buyAt(req.getBuyAt())
                .price(req.getPrice())
                .quantity(req.getQuantity())
                .isShared(position.getIsShared())
                .createdAt(LocalDateTime.now())
                .build();
        buyRepository.save(buy);
    }

    // ─── 매도 등록 ────────────────────────────────────────────────────────────────

    public void addSell(String email, Long positionId, AddSellRequest req) {
        User user = findUser(email);
        InvestmentPosition position = findPosition(positionId);
        checkOwnership(position.getUser(), user);

        int holdingQty = buyRepository.sumQuantityByPositionId(positionId)
                - sellRepository.sumQuantityByPositionId(positionId);

        if (req.getQuantity() > holdingQty) {
            throw new IllegalArgumentException("보유 수량(" + holdingQty + "주)보다 많이 매도할 수 없습니다.");
        }

        InvestmentSell sell = InvestmentSell.builder()
                .user(user)
                .position(position)
                .stockCode(position.getStockCode())
                .corpName(position.getCorpName())
                .sellAt(req.getSellAt())
                .price(req.getPrice())
                .quantity(req.getQuantity())
                .isShared(position.getIsShared())
                .createdAt(LocalDateTime.now())
                .build();
        sellRepository.save(sell);

        if (holdingQty - req.getQuantity() <= 0) {
            position.complete(req.getSellAt());
        }
    }

    // ─── 제목 수정 ────────────────────────────────────────────────────────────────

    public void updateTitle(String email, String type, Long id, UpdateTitleRequest req) {
        User user = findUser(email);
        String title = req.getTitle();
        if (title == null || title.isBlank()) throw new IllegalArgumentException("제목을 입력해주세요.");

        switch (type) {
            case "position" -> {
                InvestmentPosition position = findPosition(id);
                checkOwnership(position.getUser(), user);
                position.updateTitle(title);
            }
            default -> throw new IllegalArgumentException("잘못된 타입입니다: " + type);
        }
    }

    // ─── 공유 토글 ────────────────────────────────────────────────────────────────

    public void toggleShare(String email, String type, Long id) {
        User user = findUser(email);

        switch (type) {
            case "buy" -> {
                InvestmentBuy buy = findBuy(id);
                checkOwnership(buy.getUser(), user);
                buy.updateShared(!Boolean.TRUE.equals(buy.getIsShared()));
            }
            case "sell" -> {
                InvestmentSell sell = findSell(id);
                checkOwnership(sell.getUser(), user);
                sell.updateShared(!Boolean.TRUE.equals(sell.getIsShared()));
            }
            case "position" -> {
                InvestmentPosition position = findPosition(id);
                checkOwnership(position.getUser(), user);
                boolean newShared = !Boolean.TRUE.equals(position.getIsShared());
                position.updateShared(newShared);
                buyRepository.findByPosition(position).forEach(b -> b.updateShared(newShared));
                sellRepository.findByPosition(position).forEach(s -> s.updateShared(newShared));
            }
            default -> throw new IllegalArgumentException("잘못된 타입입니다: " + type);
        }
    }

    // ─── 메모 저장 ────────────────────────────────────────────────────────────────

    public void updateNote(String email, Long positionId, UpdateNoteRequest req) {
        User user = findUser(email);
        InvestmentPosition position = findPosition(positionId);
        checkOwnership(position.getUser(), user);
        position.updateNote(req.getNote());
    }

    // ─── 삭제 ─────────────────────────────────────────────────────────────────────

    public void delete(String email, String type, Long id) {
        User user = findUser(email);
        switch (type) {
            case "buy" -> deleteBuy(user, id);
            case "sell" -> deleteSell(user, id);
            case "position" -> deletePosition(user, id);
            default -> throw new IllegalArgumentException("잘못된 타입입니다: " + type);
        }
    }

    private void deleteBuy(User user, Long buyId) {
        InvestmentBuy buy = findBuy(buyId);
        checkOwnership(buy.getUser(), user);

        InvestmentPosition position = buy.getPosition();
        Long positionId = position.getId();

        int holdingQty = buyRepository.sumQuantityByPositionId(positionId)
                - sellRepository.sumQuantityByPositionId(positionId);
        int sellCount = sellRepository.countByPosition(position);
        int buyCount = buyRepository.countByPosition(position);

        if (sellCount > 0 && buy.getQuantity() > holdingQty) {
            throw new IllegalArgumentException("이미 매도된 수량이 포함되어 있어 삭제할 수 없습니다.");
        }

        boolean needsFirstBuyAtUpdate = buy.getBuyAt() != null
                && buy.getBuyAt().equals(position.getFirstBuyAt());
        LocalDateTime deletedBuyAt = buy.getBuyAt();

        if (sellCount == 0 && buyCount == 1) {
            buyRepository.delete(buy);
            positionRepository.delete(position);
            return;
        }

        buyRepository.delete(buy);

        if (sellCount == 0) {
            if (buyRepository.sumQuantityByPositionId(positionId) <= 0
                    || buyRepository.sumAmountByPositionId(positionId) <= 0) {
                positionRepository.delete(position);
                return;
            }
        }

        if (needsFirstBuyAtUpdate) {
            buyRepository.findEarliestBuyAtAfter(positionId, deletedBuyAt)
                    .ifPresent(position::updateFirstBuyAt);
        }
    }

    private void deleteSell(User user, Long sellId) {
        InvestmentSell sell = findSell(sellId);
        checkOwnership(sell.getUser(), user);

        InvestmentPosition position = sell.getPosition();
        Long positionId = position.getId();

        sellRepository.delete(sell);

        int holdingQty = buyRepository.sumQuantityByPositionId(positionId)
                - sellRepository.sumQuantityByPositionId(positionId);

        if (holdingQty > 0 && position.getState() == JournalState.완료) {
            LocalDateTime newFinalSellAt = sellRepository.findLatestSellAt(positionId).orElse(null);
            position.reopen(newFinalSellAt);
        }
    }

    private void deletePosition(User user, Long positionId) {
        InvestmentPosition position = findPosition(positionId);
        checkOwnership(position.getUser(), user);
        buyRepository.deleteByPosition(position);
        sellRepository.deleteByPosition(position);
        positionRepository.delete(position);
    }

    // ─── 내부 헬퍼 ───────────────────────────────────────────────────────────────

    private User findUser(String email) {
        return userRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
    }

    private InvestmentPosition findPosition(Long id) {
        return positionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("거래내역을 찾을 수 없습니다."));
    }

    private InvestmentBuy findBuy(Long id) {
        return buyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("매수 기록을 찾을 수 없습니다."));
    }

    private InvestmentSell findSell(Long id) {
        return sellRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("매도 기록을 찾을 수 없습니다."));
    }

    private void checkOwnership(User owner, User requester) {
        if (!owner.getId().equals(requester.getId())) {
            throw new IllegalArgumentException("권한이 없습니다.");
        }
    }

    private boolean matchesSearch(String title, String corpName, String lq) {
        if (lq.isEmpty()) return true;
        return (title != null && title.toLowerCase().contains(lq))
                || (corpName != null && corpName.toLowerCase().contains(lq));
    }

    private boolean matchesDate(LocalDate date, LocalDate from, LocalDate to) {
        if (date == null) return from == null && to == null;
        if (from != null && date.isBefore(from)) return false;
        if (to != null && date.isAfter(to)) return false;
        return true;
    }

    // 실시간 시세 조회 (네이버 폴링 API), 실패 시 최근 종가로 폴백
    private Long resolveCurrentPrice(String stockCode) {
        Long realtime = fetchRealtimePrice(stockCode);
        if (realtime != null) return realtime;

        return stockPriceRepository.findTopBySrtnCdOrderByBasDtDesc(stockCode)
                .map(sp -> sp.getClpr())
                .orElse(null);
    }

    private Long fetchRealtimePrice(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) return null;

        try {
            URI uri = UriComponentsBuilder.fromHttpUrl(NAVER_REALTIME_API)
                    .queryParam("query", "SERVICE_POPULAR_ITEM:" + stockCode)
                    .build()
                    .toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/149.0.0.0 Safari/537.36");
            headers.set("Referer", "https://finance.naver.com/");
            headers.setAccept(List.of(MediaType.ALL));

            ResponseEntity<String> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            Root root = new ObjectMapper().readValue(response.getBody(), Root.class);
            Data data = root.getResult().getAreas().get(0).getDatas().get(0);
            return (long) data.getNv();
        } catch (Exception e) {
            log.warn("네이버 실시간 시세 조회 실패 - stockCode={}, 최근 종가로 대체: {}", stockCode, e.toString());
            return null;
        }
    }

    private LocalDateTime sortTime(JournalListItemDto item) {
        if ("buy".equals(item.getType())) return item.getBuyAt();
        if ("sell".equals(item.getType())) return item.getSellAt();
        return item.getFinalSellAt() != null ? item.getFinalSellAt() : item.getFirstBuyAt();
    }

    private JournalListItemDto toBuyItem(InvestmentBuy b) {
        return JournalListItemDto.builder()
                .type("buy")
                .id(b.getId())
                .journalId(b.getPosition().getId())
                .title(b.getPosition().getTitle())
                .stockCode(b.getStockCode())
                .corpName(b.getCorpName())
                .isShared(b.getIsShared())
                .buyAt(b.getBuyAt())
                .price(b.getPrice())
                .quantity(b.getQuantity())
                .build();
    }

    private JournalListItemDto toSellItem(InvestmentSell s) {
        return JournalListItemDto.builder()
                .type("sell")
                .id(s.getId())
                .journalId(s.getPosition().getId())
                .title(s.getPosition().getTitle())
                .stockCode(s.getStockCode())
                .corpName(s.getCorpName())
                .isShared(s.getIsShared())
                .sellAt(s.getSellAt())
                .price(s.getPrice())
                .quantity(s.getQuantity())
                .build();
    }

    private JournalListItemDto toPositionItem(InvestmentPosition p) {
        Long positionId = p.getId();
        int totalBuyQty = buyRepository.sumQuantityByPositionId(positionId);
        int totalSellQty = sellRepository.sumQuantityByPositionId(positionId);
        long usedAmount = buyRepository.sumAmountByPositionId(positionId);
        long soldAmount = sellRepository.sumAmountByPositionId(positionId);

        Long currentPrice = resolveCurrentPrice(p.getStockCode());

        return JournalListItemDto.builder()
                .type("position")
                .id(positionId)
                .journalId(positionId)
                .title(p.getTitle())
                .stockCode(p.getStockCode())
                .corpName(p.getCorpName())
                .isShared(p.getIsShared())
                .state(p.getState().name())
                .firstBuyAt(p.getFirstBuyAt())
                .finalSellAt(p.getFinalSellAt())
                .note(p.getNote())
                .holdingQty(totalBuyQty - totalSellQty)
                .usedAmount(usedAmount)
                .soldAmount(soldAmount)
                .pnl(soldAmount - usedAmount)
                .currentPrice(currentPrice)
                .build();
    }
}
