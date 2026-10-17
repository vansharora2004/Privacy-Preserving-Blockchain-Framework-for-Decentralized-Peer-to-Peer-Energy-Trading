package com.energytrading.dto;

import java.math.BigDecimal;

/**
 * Aggregate public market figures. Quantity and price figures are withheld (null) unless at least
 * {@code minimumSellers} distinct sellers have open offers, so a single household cannot be singled out.
 */
public record MarketSummaryView(long openOffers, BigDecimal totalOfferedKwh, BigDecimal minPricePerKwh,
                                BigDecimal maxPricePerKwh, long confirmedTradesLast24h, int minimumSellers,
                                boolean figuresWithheld) {
}
