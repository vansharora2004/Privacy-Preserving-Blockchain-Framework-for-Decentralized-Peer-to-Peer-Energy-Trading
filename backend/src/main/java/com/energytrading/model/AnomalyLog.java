package com.energytrading.model;

import com.energytrading.model.enums.AnomalyReviewStatus;
import com.energytrading.model.enums.AnomalySeverity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

@Entity
@Table(name = "anomaly_logs", indexes = {
    @Index(name = "idx_anomaly_target", columnList = "targetReferenceId"),
    @Index(name = "idx_anomaly_rule_code", columnList = "ruleCode"),
    @Index(name = "idx_anomaly_review_status", columnList = "reviewStatus")
})
public class AnomalyLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 50)
    private String eventType;

    @Column(length = 64)
    private String targetReferenceId;

    @NotBlank
    @Column(nullable = false, length = 64)
    private String ruleCode;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AnomalySeverity severity = AnomalySeverity.MEDIUM;

    @NotBlank
    @Column(nullable = false, length = 500)
    private String description;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AnomalyReviewStatus reviewStatus = AnomalyReviewStatus.DETECTED;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public AnomalyLog() {
    }

    public AnomalyLog(String eventType, String targetReferenceId, String ruleCode,
                      AnomalySeverity severity, String description,
                      AnomalyReviewStatus reviewStatus) {
        this.eventType = eventType;
        this.targetReferenceId = targetReferenceId;
        this.ruleCode = ruleCode;
        this.severity = severity != null ? severity : AnomalySeverity.MEDIUM;
        this.description = description;
        this.reviewStatus = reviewStatus != null ? reviewStatus : AnomalyReviewStatus.DETECTED;
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

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getTargetReferenceId() {
        return targetReferenceId;
    }

    public void setTargetReferenceId(String targetReferenceId) {
        this.targetReferenceId = targetReferenceId;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public void setRuleCode(String ruleCode) {
        this.ruleCode = ruleCode;
    }

    public AnomalySeverity getSeverity() {
        return severity;
    }

    public void setSeverity(AnomalySeverity severity) {
        this.severity = severity;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public AnomalyReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(AnomalyReviewStatus reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
