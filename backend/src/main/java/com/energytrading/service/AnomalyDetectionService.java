package com.energytrading.service;

import com.energytrading.config.AnomalyProperties;
import com.energytrading.dto.FindingView;
import com.energytrading.dto.OfferCheckResponse;
import com.energytrading.model.AnomalyLog;
import com.energytrading.model.User;
import com.energytrading.model.enums.AnomalyReviewStatus;
import com.energytrading.privacy.PseudonymService;
import com.energytrading.repository.AnomalyLogRepository;
import com.energytrading.repository.MeterReadingRepository;
import com.energytrading.service.anomaly.AnomalyRule;
import com.energytrading.service.anomaly.RuleFinding;
import com.energytrading.service.anomaly.RuleOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Explainable, configurable rule engine. Each check returns {@link RuleFinding}s (rule code,
 * severity, outcome, explanation) instead of throwing, so callers can combine results.
 * <p>
 * Reading checks are invoked by {@link SmartMeterValidationService}, which combines them with
 * its structural checks into a single outcome; this keeps validation and anomaly detection from
 * producing contradictory results. Every finding is persisted as an {@link AnomalyLog} whose
 * subject is a household pseudonym, never a household code, name or e-mail address.
 */
@Service
public class AnomalyDetectionService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionService.class);
    private static final int MAX_DESCRIPTION = 500;
    private static final int PRICE_SCALE = 2;

    private final AnomalyProperties properties;
    private final MeterReadingRepository meterReadingRepository;
    private final AnomalyLogRepository anomalyLogRepository;
    private final PseudonymService pseudonymService;
    private final AuditService auditService;
    private final Clock clock;

    public AnomalyDetectionService(AnomalyProperties properties,
                                   MeterReadingRepository meterReadingRepository,
                                   AnomalyLogRepository anomalyLogRepository,
                                   PseudonymService pseudonymService,
                                   AuditService auditService,
                                   Clock clock) {
        this.properties = properties;
        this.meterReadingRepository = meterReadingRepository;
        this.anomalyLogRepository = anomalyLogRepository;
        this.pseudonymService = pseudonymService;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Rule F: invalid quantities
    // ------------------------------------------------------------------

    /** Negative (or zero when not allowed) quantities, and more decimal places than {@code maxScale}. */
    public List<RuleFinding> checkQuantity(String field, BigDecimal value, boolean zeroAllowed, int maxScale) {
        List<RuleFinding> findings = new ArrayList<>();
        if (value == null) {
            return findings;
        }
        if (value.signum() < 0 || (value.signum() == 0 && !zeroAllowed)) {
            findings.add(new RuleFinding(AnomalyRule.INVALID_QUANTITY, field,
                    field + " must be " + (zeroAllowed ? "zero or greater" : "greater than zero")
                            + " (received " + value.toPlainString() + ")"));
        }
        if (value.stripTrailingZeros().scale() > maxScale) {
            findings.add(new RuleFinding(AnomalyRule.INVALID_PRECISION, field,
                    field + " has more than " + maxScale + " decimal places; values are not rounded automatically"));
        }
        return findings;
    }

    // ------------------------------------------------------------------
    // Rule B: suspicious prices
    // ------------------------------------------------------------------

    /** Non-positive prices are rejected; prices above the configured maximum are flagged for review. */
    public List<RuleFinding> checkPrice(BigDecimal pricePerKwh) {
        List<RuleFinding> findings = new ArrayList<>();
        if (pricePerKwh == null) {
            return findings;
        }
        if (pricePerKwh.signum() <= 0) {
            findings.add(new RuleFinding(AnomalyRule.INVALID_PRICE, "pricePerKwh",
                    "pricePerKwh must be greater than zero (received " + pricePerKwh.toPlainString() + ")"));
            return findings;
        }
        if (pricePerKwh.stripTrailingZeros().scale() > PRICE_SCALE) {
            findings.add(new RuleFinding(AnomalyRule.INVALID_PRECISION, "pricePerKwh",
                    "pricePerKwh has more than " + PRICE_SCALE + " decimal places"));
        }
        if (pricePerKwh.compareTo(properties.maxPricePerKwh()) > 0) {
            findings.add(new RuleFinding(AnomalyRule.SUSPICIOUS_PRICE, "pricePerKwh",
                    "pricePerKwh " + pricePerKwh.toPlainString() + " exceeds the configured maximum of "
                            + properties.maxPricePerKwh().toPlainString() + " per kWh; flagged for review"));
        }
        return findings;
    }

    // ------------------------------------------------------------------
    // Rule A: energy overselling
    // ------------------------------------------------------------------

    /** A proposed quantity larger than the validated (sellable) surplus is rejected. */
    public Optional<RuleFinding> checkOverselling(BigDecimal proposedKwh, BigDecimal validatedSurplusKwh) {
        if (proposedKwh == null || proposedKwh.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal surplus = validatedSurplusKwh == null ? BigDecimal.ZERO : validatedSurplusKwh.max(BigDecimal.ZERO);
        if (proposedKwh.compareTo(surplus) > 0) {
            return Optional.of(new RuleFinding(AnomalyRule.OVERSELLING, "quantityKwh",
                    "Proposed quantity " + proposedKwh.toPlainString() + " kWh exceeds the validated available surplus of "
                            + surplus.stripTrailingZeros().toPlainString() + " kWh"));
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // Rule C: sudden reading changes
    // ------------------------------------------------------------------

    /**
     * Compares a value with the same value in the last validated reading. Flags an absolute change
     * above {@code max-reading-jump-kwh}, or a relative change above {@code max-relative-change};
     * the relative check is skipped when the previous value is below the configured baseline,
     * which also avoids dividing by zero. Missing history yields no finding.
     */
    public Optional<RuleFinding> checkSuddenChange(String field, BigDecimal previous, BigDecimal current) {
        if (previous == null || current == null) {
            return Optional.empty();
        }
        BigDecimal delta = current.subtract(previous).abs();
        if (delta.compareTo(properties.maxReadingJumpKwh()) > 0) {
            return Optional.of(new RuleFinding(AnomalyRule.SUDDEN_READING_CHANGE, field,
                    field + " changed by " + plain(delta) + " kWh since the last validated reading (limit "
                            + plain(properties.maxReadingJumpKwh()) + " kWh)"));
        }
        if (previous.signum() > 0 && previous.compareTo(properties.relativeChangeMinBaselineKwh()) >= 0) {
            BigDecimal ratio = delta.divide(previous, 6, RoundingMode.HALF_UP);
            if (ratio.compareTo(properties.maxRelativeChange()) > 0) {
                return Optional.of(new RuleFinding(AnomalyRule.SUDDEN_READING_CHANGE, field,
                        field + " changed by " + percent(ratio) + " since the last validated reading (limit "
                                + percent(properties.maxRelativeChange()) + ")"));
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // Rule D: excessive activity
    // ------------------------------------------------------------------

    /** Counts stored readings in the configured window with a single COUNT query. */
    public Optional<RuleFinding> checkReadingActivity(Long householdId) {
        if (householdId == null) {
            return Optional.empty();
        }
        LocalDateTime since = LocalDateTime.now(clock).minus(properties.activityWindow());
        long recent = meterReadingRepository.countByHouseholdIdAndCreatedAtAfter(householdId, since);
        if (recent + 1 > properties.maxReadingsPerWindow()) {
            return Optional.of(new RuleFinding(AnomalyRule.EXCESSIVE_ACTIVITY, null,
                    "This would be reading " + (recent + 1) + " within " + properties.activityWindow().toMinutes()
                            + " minutes (limit " + properties.maxReadingsPerWindow() + "); flagged for review"));
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // Rule E: duplicate / replayed identifiers
    // ------------------------------------------------------------------

    /** Reading IDs are unique system-wide; sequence numbers are unique per household. */
    public List<RuleFinding> checkDuplicateReading(Long householdId, String readingId, Long sequenceNo) {
        List<RuleFinding> findings = new ArrayList<>();
        if (readingId != null && meterReadingRepository.existsByReadingId(readingId)) {
            findings.add(new RuleFinding(AnomalyRule.DUPLICATE_READING_ID, "readingId",
                    "readingId has already been used by an earlier submission (possible replay)"));
        }
        if (householdId != null && sequenceNo != null
                && meterReadingRepository.existsByHouseholdIdAndSequenceNo(householdId, sequenceNo)) {
            findings.add(new RuleFinding(AnomalyRule.DUPLICATE_SEQUENCE, "sequenceNo",
                    "sequenceNo " + sequenceNo + " has already been used by this household (possible replay)"));
        }
        return findings;
    }

    // ------------------------------------------------------------------
    // Offer pre-check (rules A, B, F) - no offer is created in Batch 2
    // ------------------------------------------------------------------

    public OfferCheckResponse evaluateProposedOffer(User seller, BigDecimal quantityKwh, BigDecimal pricePerKwh, int maxQuantityScale) {
        BigDecimal surplus = EnergyValues.toBigDecimal(seller.getCurrentSurplusKwh());
        List<RuleFinding> findings = new ArrayList<>(checkQuantity("quantityKwh", quantityKwh, false, maxQuantityScale));
        findings.addAll(checkPrice(pricePerKwh));
        checkOverselling(quantityKwh, surplus).ifPresent(findings::add);

        String pseudonym = pseudonymService.householdPseudonym(seller.getHouseholdCode());
        record("OFFER_CHECK", pseudonym, null, findings);
        String outcome = overallOutcome(findings);
        auditService.record(seller.getHouseholdCode(), "OFFER_PRECHECK", outcome, "rules=" + codes(findings));
        return new OfferCheckResponse(pseudonym, outcome, surplus == null ? BigDecimal.ZERO : surplus,
                findings.stream().map(FindingView::from).toList());
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /**
     * Stores one anomaly log per finding.
     *
     * @param eventType        what was evaluated, e.g. {@code METER_READING} or {@code OFFER_CHECK}
     * @param subjectPseudonym household pseudonym (never a household code)
     * @param recordLabel      non-identifying record label such as {@code "sequence 12"}, or null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<AnomalyLog> record(String eventType, String subjectPseudonym, String recordLabel, List<RuleFinding> findings) {
        List<AnomalyLog> logs = findings.stream().map(f -> new AnomalyLog(eventType, subjectPseudonym, f.ruleCode(),
                f.severity(), truncate((recordLabel != null ? "[" + recordLabel + "] " : "") + f.message()),
                AnomalyReviewStatus.DETECTED)).toList();
        if (!logs.isEmpty()) {
            log.info("{} anomaly finding(s) for {} {}: {}", logs.size(), eventType, subjectPseudonym, codes(findings));
        }
        return anomalyLogRepository.saveAll(logs);
    }

    public static String overallOutcome(List<RuleFinding> findings) {
        if (findings.stream().anyMatch(f -> f.outcome() == RuleOutcome.REJECTED)) {
            return RuleOutcome.REJECTED.name();
        }
        return findings.isEmpty() ? "PASSED" : RuleOutcome.FLAGGED.name();
    }

    public static String codes(List<RuleFinding> findings) {
        return findings.stream().map(RuleFinding::ruleCode).distinct().toList().toString();
    }

    private static String truncate(String text) {
        return text.length() > MAX_DESCRIPTION ? text.substring(0, MAX_DESCRIPTION) : text;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String percent(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
