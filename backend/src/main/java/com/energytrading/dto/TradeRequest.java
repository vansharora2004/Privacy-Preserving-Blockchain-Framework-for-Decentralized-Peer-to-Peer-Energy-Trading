package com.energytrading.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Purchase request. Price, parties and totals are taken from the server-side offer, never from the client. */
public record TradeRequest(@NotBlank String offerId, @NotNull BigDecimal quantityKwh) {
}
