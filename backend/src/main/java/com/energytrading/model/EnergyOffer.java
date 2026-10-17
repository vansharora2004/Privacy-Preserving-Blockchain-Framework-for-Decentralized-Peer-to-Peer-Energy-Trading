package com.energytrading.model;

import com.energytrading.model.enums.OfferStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "energy_offers",
    uniqueConstraints = {
        // Idempotent offer creation: one offer per seller and client request key
        @UniqueConstraint(name = "uk_offer_seller_request", columnNames = {"seller_id", "client_request_id"})
    },
    indexes = {
    @Index(name = "idx_offer_status_expiry", columnList = "status, expires_at"),
    @Index(name = "idx_offer_id", columnList = "offerId", unique = true),
    @Index(name = "idx_offer_status", columnList = "status"),
    @Index(name = "idx_offer_seller", columnList = "seller_id"),
    @Index(name = "idx_offer_source_reading", columnList = "source_reading_id")
})
public class EnergyOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false, foreignKey = @ForeignKey(name = "fk_offer_seller"))
    private User seller;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_reading_id", foreignKey = @ForeignKey(name = "fk_offer_source_reading"))
    private MeterReading sourceReading;

    @NotBlank
    @Column(nullable = false, unique = true, length = 64)
    private String offerId;

    @NotNull
    @Positive
    @Column(nullable = false)
    private Double quantityKwh;

    @NotNull
    @PositiveOrZero
    @Column(nullable = false)
    private Double remainingKwh;

    @NotNull
    @Positive
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal pricePerKwh;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private OfferStatus status = OfferStatus.ACTIVE;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /** After this instant the offer can no longer be traded (set to EXPIRED by the scheduler). */
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /** Client-supplied Idempotency-Key, unique per seller; null when the client sent none. */
    @Column(length = 64)
    private String clientRequestId;

    public EnergyOffer() {
    }

    public EnergyOffer(User seller, MeterReading sourceReading, String offerId,
                       Double quantityKwh, Double remainingKwh, BigDecimal pricePerKwh,
                       OfferStatus status) {
        this.seller = seller;
        this.sourceReading = sourceReading;
        this.offerId = offerId;
        this.quantityKwh = quantityKwh;
        this.remainingKwh = remainingKwh != null ? remainingKwh : quantityKwh;
        this.pricePerKwh = pricePerKwh;
        this.status = status != null ? status : OfferStatus.ACTIVE;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.expiresAt == null) {
            // Same default lifetime as migration V002 uses for pre-existing offers
            this.expiresAt = this.createdAt.plusHours(24);
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getSeller() {
        return seller;
    }

    public void setSeller(User seller) {
        this.seller = seller;
    }

    public MeterReading getSourceReading() {
        return sourceReading;
    }

    public void setSourceReading(MeterReading sourceReading) {
        this.sourceReading = sourceReading;
    }

    public String getOfferId() {
        return offerId;
    }

    public void setOfferId(String offerId) {
        this.offerId = offerId;
    }

    public Double getQuantityKwh() {
        return quantityKwh;
    }

    public void setQuantityKwh(Double quantityKwh) {
        this.quantityKwh = quantityKwh;
    }

    public Double getRemainingKwh() {
        return remainingKwh;
    }

    public void setRemainingKwh(Double remainingKwh) {
        this.remainingKwh = remainingKwh;
    }

    public BigDecimal getPricePerKwh() {
        return pricePerKwh;
    }

    public void setPricePerKwh(BigDecimal pricePerKwh) {
        this.pricePerKwh = pricePerKwh;
    }

    public OfferStatus getStatus() {
        return status;
    }

    public void setStatus(OfferStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
