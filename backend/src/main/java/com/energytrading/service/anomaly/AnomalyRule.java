package com.energytrading.service.anomaly;

import com.energytrading.model.enums.AnomalySeverity;

import java.util.Optional;

/**
 * Every validation and anomaly rule, identified by a stable code ({@link #name()}).
 * <p>
 * Each rule has a fixed severity and a fixed outcome. {@link RuleOutcome#REJECTED} is a hard
 * validation or policy failure; {@link RuleOutcome#FLAGGED} marks unusual but potentially
 * legitimate activity for human review and is not an accusation of fraud. Because the outcome
 * is a property of the rule code, it can be derived from {@code anomaly_logs.rule_code}
 * without an extra column.
 */
public enum AnomalyRule {

    MISSING_REQUIRED_FIELD(AnomalySeverity.LOW, RuleOutcome.REJECTED),
    INVALID_IDENTIFIER(AnomalySeverity.LOW, RuleOutcome.REJECTED),
    INVALID_QUANTITY(AnomalySeverity.HIGH, RuleOutcome.REJECTED),
    INVALID_PRECISION(AnomalySeverity.LOW, RuleOutcome.REJECTED),
    IMPLAUSIBLE_READING(AnomalySeverity.HIGH, RuleOutcome.REJECTED),
    FUTURE_TIMESTAMP(AnomalySeverity.MEDIUM, RuleOutcome.REJECTED),
    STALE_TIMESTAMP(AnomalySeverity.HIGH, RuleOutcome.REJECTED),
    DUPLICATE_READING_ID(AnomalySeverity.HIGH, RuleOutcome.REJECTED),
    DUPLICATE_SEQUENCE(AnomalySeverity.HIGH, RuleOutcome.REJECTED),
    STALE_SEQUENCE(AnomalySeverity.HIGH, RuleOutcome.REJECTED),
    CONCURRENT_DUPLICATE(AnomalySeverity.HIGH, RuleOutcome.REJECTED),
    SEQUENCE_GAP(AnomalySeverity.LOW, RuleOutcome.FLAGGED),
    SUDDEN_READING_CHANGE(AnomalySeverity.MEDIUM, RuleOutcome.FLAGGED),
    EXCESSIVE_ACTIVITY(AnomalySeverity.MEDIUM, RuleOutcome.FLAGGED),
    OVERSELLING(AnomalySeverity.HIGH, RuleOutcome.REJECTED),
    INVALID_PRICE(AnomalySeverity.HIGH, RuleOutcome.REJECTED),
    SUSPICIOUS_PRICE(AnomalySeverity.MEDIUM, RuleOutcome.FLAGGED);

    private final AnomalySeverity severity;
    private final RuleOutcome outcome;

    AnomalyRule(AnomalySeverity severity, RuleOutcome outcome) {
        this.severity = severity;
        this.outcome = outcome;
    }

    public String code() {
        return name();
    }

    public AnomalySeverity severity() {
        return severity;
    }

    public RuleOutcome outcome() {
        return outcome;
    }

    public static Optional<AnomalyRule> fromCode(String code) {
        for (AnomalyRule rule : values()) {
            if (rule.name().equals(code)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }
}
