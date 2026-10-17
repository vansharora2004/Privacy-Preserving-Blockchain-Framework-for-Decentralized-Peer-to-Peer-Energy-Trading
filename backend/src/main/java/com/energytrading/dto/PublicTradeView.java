package com.energytrading.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Public view of a CONFIRMED trade: amounts and the blockchain reference only. No household
 * pseudonym is included, so the public view cannot link a household to its on-chain trades
 * (the on-chain party references are per-trade and keyed). Amounts and timing remain public.
 */
public record PublicTradeView(BigDecimal quantityKwh, BigDecimal pricePerKwh, String txHash, Long blockNumber,
                              LocalDateTime confirmedAt) {
}
