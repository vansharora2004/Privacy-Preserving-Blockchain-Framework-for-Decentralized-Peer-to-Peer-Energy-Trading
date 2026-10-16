package com.energytrading.dto;

import java.math.BigDecimal;
import java.util.List;

/** Result of a meter-reading submission. */
public record MeterReadingSubmissionResponse(String readingId,
                                             String householdPseudonym,
                                             String status,
                                             boolean stored,
                                             BigDecimal availableKwh,
                                             BigDecimal sellableSurplusKwh,
                                             String recordHash,
                                             List<FindingView> findings,
                                             List<String> notes) {
}
