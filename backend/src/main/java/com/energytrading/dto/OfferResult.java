package com.energytrading.dto;

import java.math.BigDecimal;
import java.util.List;

/** Outcome of an offer request: PASSED or FLAGGED (offer created) or REJECTED (no offer). */
public record OfferResult(String outcome, boolean replayed, OwnOfferView offer, BigDecimal availableKwh,
                          List<FindingView> findings) {
}
