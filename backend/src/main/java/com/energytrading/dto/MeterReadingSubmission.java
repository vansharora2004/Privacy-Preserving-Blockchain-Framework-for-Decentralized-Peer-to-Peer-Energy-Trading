package com.energytrading.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A simulated smart-meter reading. {@code householdCode} is optional and, if given, must be the caller's own. */
public record MeterReadingSubmission(String householdCode,
                                     String readingId,
                                     Long sequenceNo,
                                     BigDecimal energyGeneratedKwh,
                                     BigDecimal energyConsumedKwh,
                                     LocalDateTime readingTime) {
}
