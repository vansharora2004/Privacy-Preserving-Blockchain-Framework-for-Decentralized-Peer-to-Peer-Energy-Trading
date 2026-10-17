package com.energytrading.dto;

/**
 * Public household directory entry: pseudonym only. Per-household energy availability is no
 * longer published (it tracked live generation and consumption); sellers disclose energy only
 * through the offers they create, and aggregate figures are in /api/privacy/public/market-summary.
 */
public record PublicHouseholdView(String householdPseudonym) {
}
