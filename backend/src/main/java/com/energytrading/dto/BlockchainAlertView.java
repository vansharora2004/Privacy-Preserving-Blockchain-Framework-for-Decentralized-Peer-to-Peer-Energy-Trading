package com.energytrading.dto;

import java.time.LocalDateTime;

/** An open, de-duplicated blockchain alert. */
public record BlockchainAlertView(Long id, String type, String tradeId, String detail, int occurrences,
                                  LocalDateTime firstSeenAt, LocalDateTime lastSeenAt) {
}
