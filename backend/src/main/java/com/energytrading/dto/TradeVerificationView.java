package com.energytrading.dto;

/** Comparison of a trade's database record with its on-chain record (no record contents). */
public record TradeVerificationView(String tradeId, String result, String onChainStatus, String note) {
}
