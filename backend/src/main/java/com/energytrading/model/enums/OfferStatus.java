package com.energytrading.model.enums;

public enum OfferStatus {
    ACTIVE,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED,
    /** Passed its expiresAt without being filled; unsold energy is released. */
    EXPIRED
}
