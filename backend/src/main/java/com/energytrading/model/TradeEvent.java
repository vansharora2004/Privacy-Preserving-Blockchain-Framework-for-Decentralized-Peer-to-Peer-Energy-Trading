package com.energytrading.model;

import com.energytrading.model.enums.TradeStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/** One state transition of a trade (append-only history). */
@Entity
@Table(name = "trade_events", indexes = {
    @Index(name = "idx_trade_event_trade", columnList = "trade_id, created_at")
})
public class TradeEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trade_id", nullable = false, foreignKey = @ForeignKey(name = "fk_trade_event_trade"))
    private TradeTransaction trade;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 30)
    private TradeStatus fromStatus;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private TradeStatus toStatus;

    @Column(length = 100)
    private String actor;

    @Column(length = 255)
    private String detail;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected TradeEvent() {
    }

    public TradeEvent(TradeTransaction trade, TradeStatus fromStatus, TradeStatus toStatus, String actor, String detail) {
        this.trade = trade;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actor = actor;
        this.detail = detail != null && detail.length() > 255 ? detail.substring(0, 255) : detail;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public TradeTransaction getTrade() {
        return trade;
    }

    public TradeStatus getFromStatus() {
        return fromStatus;
    }

    public TradeStatus getToStatus() {
        return toStatus;
    }

    public String getActor() {
        return actor;
    }

    public String getDetail() {
        return detail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
