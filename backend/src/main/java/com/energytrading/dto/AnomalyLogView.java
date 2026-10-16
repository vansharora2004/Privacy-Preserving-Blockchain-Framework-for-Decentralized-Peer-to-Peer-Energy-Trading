package com.energytrading.dto;

import java.time.LocalDateTime;

/** An anomaly log entry. The subject is a pseudonym, never a household code or name. */
public record AnomalyLogView(Long id,
                             String ruleCode,
                             String outcome,
                             String severity,
                             String eventType,
                             String subjectPseudonym,
                             String description,
                             String reviewStatus,
                             LocalDateTime createdAt) {
}
