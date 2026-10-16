package com.energytrading.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Private household details for the owner or an administrator. Never contains credentials. */
public record PrivateHouseholdView(String householdCode,
                                   String householdPseudonym,
                                   String displayAlias,
                                   String fullName,
                                   String email,
                                   String role,
                                   BigDecimal balance,
                                   BigDecimal currentSurplusKwh,
                                   BigDecimal currentDeficitKwh,
                                   LocalDateTime createdAt) {
}
