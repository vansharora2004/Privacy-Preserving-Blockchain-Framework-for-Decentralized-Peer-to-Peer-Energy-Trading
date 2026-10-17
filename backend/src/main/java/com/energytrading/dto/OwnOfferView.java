package com.energytrading.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** An offer as seen by its seller (or an administrator). */
public record OwnOfferView(String offerId, String status, BigDecimal quantityKwh, BigDecimal remainingKwh,
                           BigDecimal pricePerKwh, String sourceReadingId, LocalDateTime createdAt, LocalDateTime expiresAt) {
}
