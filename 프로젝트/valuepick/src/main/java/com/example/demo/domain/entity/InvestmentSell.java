package com.example.demo.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.LocalDateTime;

@Entity
@Table(name = "INVESTMENT_SELL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class InvestmentSell {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "position_id")
    private InvestmentPosition position;

    @Column(name = "stock_code")
    private String stockCode;

    @Column(name = "corp_name")
    private String corpName;

    @Column(name = "sell_at")
    private LocalDateTime sellAt;

    @Column(name = "price")
    private Long price;

    @Column(name = "quantity")
    private Integer quantity;

    @Column(name = "is_shared")
    private Boolean isShared;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public void updateShared(Boolean isShared) { this.isShared = isShared; }
}
