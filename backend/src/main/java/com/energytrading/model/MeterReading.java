package com.energytrading.model;

import com.energytrading.model.enums.ReadingValidationStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

@Entity
@Table(name = "meter_readings", indexes = {
    @Index(name = "idx_reading_id", columnList = "readingId", unique = true),
    @Index(name = "idx_household_sequence", columnList = "household_id, sequenceNo")
})
public class MeterReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "household_id", nullable = false)
    private User household;

    @NotBlank
    @Column(nullable = false, unique = true, length = 64)
    private String readingId;

    @NotNull
    @Column(nullable = false)
    private Long sequenceNo;

    @NotNull
    @Column(nullable = false)
    private Double energyGeneratedKwh;

    @NotNull
    @Column(nullable = false)
    private Double energyConsumedKwh;

    @NotNull
    @Column(nullable = false)
    private Double availableKwh;

    @NotNull
    @Column(nullable = false)
    private LocalDateTime readingTime;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReadingValidationStatus validationStatus = ReadingValidationStatus.PENDING;

    @Column(length = 255)
    private String rejectionReason;

    @Column(length = 64)
    private String recordHash;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public MeterReading() {
    }

    public MeterReading(User household, String readingId, Long sequenceNo,
                        Double energyGeneratedKwh, Double energyConsumedKwh,
                        Double availableKwh, LocalDateTime readingTime,
                        ReadingValidationStatus validationStatus, String recordHash) {
        this.household = household;
        this.readingId = readingId;
        this.sequenceNo = sequenceNo;
        this.energyGeneratedKwh = energyGeneratedKwh;
        this.energyConsumedKwh = energyConsumedKwh;
        this.availableKwh = availableKwh;
        this.readingTime = readingTime;
        this.validationStatus = validationStatus != null ? validationStatus : ReadingValidationStatus.PENDING;
        this.recordHash = recordHash;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getHousehold() {
        return household;
    }

    public void setHousehold(User household) {
        this.household = household;
    }

    public String getReadingId() {
        return readingId;
    }

    public void setReadingId(String readingId) {
        this.readingId = readingId;
    }

    public Long getSequenceNo() {
        return sequenceNo;
    }

    public void setSequenceNo(Long sequenceNo) {
        this.sequenceNo = sequenceNo;
    }

    public Double getEnergyGeneratedKwh() {
        return energyGeneratedKwh;
    }

    public void setEnergyGeneratedKwh(Double energyGeneratedKwh) {
        this.energyGeneratedKwh = energyGeneratedKwh;
    }

    public Double getEnergyConsumedKwh() {
        return energyConsumedKwh;
    }

    public void setEnergyConsumedKwh(Double energyConsumedKwh) {
        this.energyConsumedKwh = energyConsumedKwh;
    }

    public Double getAvailableKwh() {
        return availableKwh;
    }

    public void setAvailableKwh(Double availableKwh) {
        this.availableKwh = availableKwh;
    }

    public LocalDateTime getReadingTime() {
        return readingTime;
    }

    public void setReadingTime(LocalDateTime readingTime) {
        this.readingTime = readingTime;
    }

    public ReadingValidationStatus getValidationStatus() {
        return validationStatus;
    }

    public void setValidationStatus(ReadingValidationStatus validationStatus) {
        this.validationStatus = validationStatus;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public String getRecordHash() {
        return recordHash;
    }

    public void setRecordHash(String recordHash) {
        this.recordHash = recordHash;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
