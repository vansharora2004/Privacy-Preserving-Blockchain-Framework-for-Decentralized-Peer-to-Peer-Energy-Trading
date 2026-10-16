package com.energytrading.dto;

import com.energytrading.service.anomaly.RuleFinding;

/** A rule finding as returned by the API. */
public record FindingView(String ruleCode, String severity, String outcome, String field, String message) {

    public static FindingView from(RuleFinding finding) {
        return new FindingView(finding.ruleCode(), finding.severity().name(), finding.outcome().name(),
                finding.field(), finding.message());
    }
}
