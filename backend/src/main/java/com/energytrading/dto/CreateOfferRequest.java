package com.energytrading.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Offer creation request. The seller, source reading and availability are determined by the server. */
public record CreateOfferRequest(@NotNull BigDecimal quantityKwh, @NotNull BigDecimal pricePerKwh, Integer expiresInMinutes) {
}
