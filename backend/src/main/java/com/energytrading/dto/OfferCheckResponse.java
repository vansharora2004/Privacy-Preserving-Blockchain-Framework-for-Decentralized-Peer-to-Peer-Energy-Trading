package com.energytrading.dto;

import java.math.BigDecimal;
import java.util.List;

/** Outcome of an offer pre-check: PASSED, FLAGGED or REJECTED. */
public record OfferCheckResponse(String householdPseudonym,
                                 String outcome,
                                 BigDecimal validatedSurplusKwh,
                                 List<FindingView> findings) {
}
