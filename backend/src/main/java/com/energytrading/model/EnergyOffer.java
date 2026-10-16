package com.energytrading.model;

import com.energytrading.model.enums.OfferStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "energy_offers", indexes = {
    @Index(name = "idx_offer_id", columnList = "offerId", unique = true),
    @Index(name = "idx_offer_status", columnList = "status"),
    @Index(name = "idx_offer_seller", columnList = "seller_id")
})
public class EnergyOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_reading_id")
    private MeterReading sourceReading;

    @NotBlank
    @Column(nullable = false, unique = true, length = 64)
    private String offerId;

    @NotNull
    @Column(nullable = false)
    private Double quantityKwh;

    @NotNull
    @Column(nullable = false)
    private Double remainingKwh;

    @NotNull
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal pricePerKwh;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OfferStatus status = OfferStatus.ACTIVE;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

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
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
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
