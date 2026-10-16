package com.energytrading.service.anomaly;

/** Outcome of a rule that fired. */
public enum RuleOutcome {
    /** Unusual but potentially legitimate; requires human review. */
    FLAGGED,
    /** Hard validation or policy failure; the operation must not be accepted. */
    REJECTED
}
