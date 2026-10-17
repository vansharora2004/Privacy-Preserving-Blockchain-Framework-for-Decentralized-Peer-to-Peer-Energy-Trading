package com.energytrading.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * A deduplicated integrity or availability alert. While open, {@code openKey} is set (unique), so
 * repeated occurrences increment {@code occurrences} instead of creating new rows; resolving the
 * alert clears {@code openKey} so that a later recurrence opens a new alert.
 */
@Entity
@Table(name = "blockchain_alerts", indexes = {
    @Index(name = "uk_alert_open_key", columnList = "open_key", unique = true),
    @Index(name = "idx_alert_type", columnList = "alert_type, resolved_at")
})
public class BlockchainAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_type", nullable = false, length = 40)
    private String alertType;

    @Column(name = "open_key", length = 191)
    private String openKey;

    @Column(length = 64)
    private String tradeRef;

    @Column(length = 500)
    private String detail;

    @Column(nullable = false)
    private int occurrences = 1;

    @Column(nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(length = 100)
    private String resolvedBy;

    @Column(length = 255)
    private String resolution;

    protected BlockchainAlert() {
    }

    public BlockchainAlert(String alertType, String openKey, String tradeRef, String detail, LocalDateTime now) {
        this.alertType = alertType;
        this.openKey = openKey;
        this.tradeRef = tradeRef;
        setDetail(detail);
        this.firstSeenAt = now;
        this.lastSeenAt = now;
    }

    public void recur(String detail, LocalDateTime now) {
        this.occurrences++;
        this.lastSeenAt = now;
        setDetail(detail);
    }

    public void resolve(String by, String resolution, LocalDateTime now) {
        this.openKey = null;
        this.resolvedAt = now;
        this.resolvedBy = by;
        this.resolution = resolution != null && resolution.length() > 255 ? resolution.substring(0, 255) : resolution;
    }

    private void setDetail(String detail) {
        this.detail = detail != null && detail.length() > 500 ? detail.substring(0, 500) : detail;
    }

    public Long getId() { return id; }
    public String getAlertType() { return alertType; }
    public String getOpenKey() { return openKey; }
    public String getTradeRef() { return tradeRef; }
    public String getDetail() { return detail; }
    public int getOccurrences() { return occurrences; }
    public LocalDateTime getFirstSeenAt() { return firstSeenAt; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public String getResolvedBy() { return resolvedBy; }
    public String getResolution() { return resolution; }
}
