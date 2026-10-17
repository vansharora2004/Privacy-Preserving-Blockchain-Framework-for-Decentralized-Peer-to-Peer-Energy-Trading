package com.energytrading.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Marketplace listing: the seller appears only as a pseudonym. */
public record OfferView(String offerId, String sellerPseudonym, BigDecimal remainingKwh, BigDecimal pricePerKwh,
                        LocalDateTime expiresAt, LocalDateTime createdAt) {
}
