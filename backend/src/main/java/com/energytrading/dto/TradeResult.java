package com.energytrading.dto;

import java.util.List;

/** Outcome of a purchase request: the trade (if one was created) and the rule findings. */
public record TradeResult(String outcome, boolean replayed, TradeView trade, List<FindingView> findings) {
}
