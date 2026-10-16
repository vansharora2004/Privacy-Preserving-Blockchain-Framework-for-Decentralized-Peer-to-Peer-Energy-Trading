package com.energytrading.dto;

import com.energytrading.model.enums.AnomalyReviewStatus;
import jakarta.validation.constraints.NotNull;

/** Administrator update of an anomaly review status. */
public record AnomalyReviewRequest(@NotNull AnomalyReviewStatus reviewStatus) {
}
