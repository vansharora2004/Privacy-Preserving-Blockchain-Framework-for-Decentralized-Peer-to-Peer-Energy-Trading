package com.energytrading.service.anomaly;

import com.energytrading.model.enums.AnomalySeverity;

import java.util.Objects;

/**
 * One rule that fired: its stable code, the affected field (if any) and a human-readable
 * explanation. Messages must never contain names, e-mail addresses, credentials or household codes.
 */
public record RuleFinding(AnomalyRule rule, String field, String message) {

    public RuleFinding {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(message, "message");
    }

    public String ruleCode() {
        return rule.code();
    }

    public AnomalySeverity severity() {
        return rule.severity();
    }

    public RuleOutcome outcome() {
        return rule.outcome();
    }
}
