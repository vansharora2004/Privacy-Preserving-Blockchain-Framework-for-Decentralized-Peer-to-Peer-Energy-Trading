package com.energytrading.dto;

import java.math.BigDecimal;

/** Public, pseudonymous household view: no names, e-mail, household code, database ID or raw readings. */
public record PublicHouseholdView(String householdPseudonym, BigDecimal availableSurplusKwh) {
}
