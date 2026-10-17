package com.energytrading.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** A trade with its blockchain record and history. Parties appear only as pseudonyms. */
public record TradeView(String tradeId, String offerId, String yourRole, String sellerPseudonym, String buyerPseudonym,
                        BigDecimal quantityKwh, BigDecimal pricePerKwh, BigDecimal totalAmount, String status,
                        String statusDetail, Blockchain blockchain, LocalDateTime createdAt, LocalDateTime updatedAt,
                        LocalDateTime confirmedAt, List<TradeEventView> history) {

    public record Blockchain(String txHash, Long blockNumber, Long chainId, String contractAddress,
                             int submissionAttempts, LocalDateTime submittedAt, String recordDigest) {
    }
}
