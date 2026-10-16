package com.energytrading.service;

import com.energytrading.model.enums.ReadingValidationStatus;
import com.energytrading.service.anomaly.RuleFinding;
import com.energytrading.service.anomaly.RuleOutcome;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of validating one meter reading.
 * <ul>
 *   <li>{@code REJECTED} if any finding is a hard failure; the reading is not stored.</li>
 *   <li>{@code FLAGGED} if only review findings fired; the reading is stored for review but
 *       is <b>not</b> treated as validated (it does not update the household's surplus).</li>
 *   <li>{@code VALID} if no rule fired.</li>
 * </ul>
 *
 * @param notes              informational remarks that are not findings (e.g. "first reading")
 * @param availableKwh       generated minus consumed; negative means a deficit
 * @param sellableSurplusKwh {@code max(0, availableKwh)}; consumed energy is never sellable
 */
public record ReadingValidationResult(ReadingValidationStatus status,
                                      List<RuleFinding> findings,
                                      List<String> notes,
                                      BigDecimal availableKwh,
                                      BigDecimal sellableSurplusKwh) {

    public ReadingValidationResult {
        findings = List.copyOf(findings);
        notes = List.copyOf(notes);
    }

    public static ReadingValidationResult of(List<RuleFinding> findings, List<String> notes, BigDecimal availableKwh) {
        BigDecimal sellable = availableKwh == null ? null : availableKwh.max(BigDecimal.ZERO);
        return new ReadingValidationResult(statusOf(findings), findings, notes, availableKwh, sellable);
    }

    public ReadingValidationResult withFinding(RuleFinding finding) {
        List<RuleFinding> all = new ArrayList<>(findings);
        all.add(finding);
        return new ReadingValidationResult(statusOf(all), all, notes, availableKwh, sellableSurplusKwh);
    }

    public boolean isRejected() {
        return status == ReadingValidationStatus.REJECTED;
    }

    public static ReadingValidationStatus statusOf(List<RuleFinding> findings) {
        if (findings.stream().anyMatch(f -> f.outcome() == RuleOutcome.REJECTED)) {
            return ReadingValidationStatus.REJECTED;
        }
        if (!findings.isEmpty()) {
            return ReadingValidationStatus.FLAGGED;
        }
        return ReadingValidationStatus.VALID;
    }
}
