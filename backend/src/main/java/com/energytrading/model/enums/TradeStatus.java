package com.energytrading.model.enums;

/**
 * Trade lifecycle. Reserved energy and escrowed funds are held in PENDING, FLAGGED, SUBMITTED and NEEDS_REVIEW,
 * and released in FAILED, CANCELLED, EXPIRED and REJECTED. Only CONFIRMED (successful blockchain
 * receipt) completes the sale.
 */
public enum TradeStatus {
    /** Energy reserved and funds escrowed; awaiting blockchain submission. */
    PENDING,
    /** Recorded on-chain with a successful receipt; seller credited. */
    CONFIRMED,
    /** Refused (hard rule or administrator decision); reservation released. */
    REJECTED,
    /** Held for administrator review; reservation kept until approved, rejected or expired. */
    FLAGGED,
    /** Signed transaction persisted and broadcast (or outcome uncertain); awaiting receipt or reconciliation. */
    SUBMITTED,
    /** Definite blockchain failure (e.g. reverted); reservation released. */
    FAILED,
    /** Cancelled before submission by the buyer, the seller (offer cancelled) or an administrator. */
    CANCELLED,
    /** Reservation timed out before it could be submitted. */
    EXPIRED,
    /**
     * The blockchain outcome cannot be established safely (deployment changed, conflicting or
     * incomplete evidence). Reservation and escrow stay held; routine jobs never submit, expire,
     * confirm or release it. Only an audited administrator resolution leaves this state.
     */
    NEEDS_REVIEW
}
