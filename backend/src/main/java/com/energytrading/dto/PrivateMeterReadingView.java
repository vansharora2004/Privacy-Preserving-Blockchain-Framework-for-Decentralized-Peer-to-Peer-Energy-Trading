package com.energytrading.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A stored meter reading, visible only to its household and administrators. */
public record PrivateMeterReadingView(String readingId,
                                      Long sequenceNo,
                                      BigDecimal energyGeneratedKwh,
                                      BigDecimal energyConsumedKwh,
                                      BigDecimal availableKwh,
                                      LocalDateTime readingTime,
                                      String validationStatus,
                                      String reviewNote,
                                      String recordHash,
                                      LocalDateTime createdAt) {
}
