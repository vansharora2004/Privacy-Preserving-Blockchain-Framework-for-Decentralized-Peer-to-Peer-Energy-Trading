package com.energytrading.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** A proposed offer to evaluate against the anomaly rules (no offer is created in Batch 2). */
public record OfferCheckRequest(@NotNull BigDecimal quantityKwh, @NotNull BigDecimal pricePerKwh) {
}
