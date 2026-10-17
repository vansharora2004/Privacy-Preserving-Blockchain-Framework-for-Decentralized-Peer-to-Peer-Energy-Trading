package com.energytrading.dto;

import java.time.LocalDateTime;

/** One trade state transition. The actor is a role label (BUYER, SELLER, ADMIN, SYSTEM, BLOCKCHAIN), never a household code. */
public record TradeEventView(String fromStatus, String toStatus, String actor, String detail, LocalDateTime at) {
}
