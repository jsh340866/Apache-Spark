package com.example.demo.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.LocalDateTime;

@Entity
@Table(name = "INVESTMENT_POSITION")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class InvestmentPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "title")
    private String title;

    @Column(name = "stock_code")
    private String stockCode;

    @Column(name = "corp_name")
    private String corpName;

    @Column(name = "first_buy_at")
    private LocalDateTime firstBuyAt;

    @Column(name = "final_sell_at")
    private LocalDateTime finalSellAt;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "is_shared")
    private Boolean isShared;

    @Enumerated(EnumType.STRING)
    @Column(name = "state")
    private JournalState state;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public void updateTitle(String title) { this.title = title; }
    public void updateNote(String note) { this.note = note; }
    public void updateFirstBuyAt(LocalDateTime firstBuyAt) { this.firstBuyAt = firstBuyAt; }
    public void updateShared(Boolean isShared) { this.isShared = isShared; }

    public void complete(LocalDateTime finalSellAt) {
        this.state = JournalState.완료;
        this.finalSellAt = finalSellAt;
    }

    public void reopen(LocalDateTime finalSellAt) {
        this.state = JournalState.보유;
        this.finalSellAt = finalSellAt;
    }
}
