package com.energytrading.service;

import com.energytrading.config.MeterValidationProperties;
import com.energytrading.dto.MeterReadingSubmission;
import com.energytrading.model.MeterReading;
import com.energytrading.model.User;
import com.energytrading.model.enums.ReadingValidationStatus;
import com.energytrading.repository.MeterReadingRepository;
import com.energytrading.service.anomaly.AnomalyRule;
import com.energytrading.service.anomaly.RuleFinding;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Validates a simulated smart-meter reading before it is accepted. Read-only: it never stores
 * the reading or modifies values (see {@link MeterReadingService} for persistence).
 * <p>
 * Checks, in order:
 * <ol>
 *   <li>Required fields and identifier format.</li>
 *   <li>Numeric rules: non-negative energy, at most {@code app.meter.max-scale} decimals.</li>
 *   <li>Plausibility: per-reading maxima and timestamp window (future skew, maximum age).</li>
 *   <li>Duplicate / replay: reused reading ID or household sequence number.</li>
 *   <li>Continuity with the household's previous readings: older sequence numbers and
 *       non-increasing timestamps are rejected; sequence gaps and sudden changes are flagged.</li>
 *   <li>Excessive activity in the configured window (flagged).</li>
 * </ol>
 * Available energy is {@code generated - consumed}; only a positive balance is sellable surplus,
 * so consumed energy is never treated as available for sale.
 * <p>
 * Software checks improve confidence but cannot prove that a physical meter is truthful.
 */
@Service
public class SmartMeterValidationService {

    private static final Pattern READING_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");

    private final MeterValidationProperties properties;
    private final MeterReadingRepository meterReadingRepository;
    private final AnomalyDetectionService anomalyDetectionService;
    private final Clock clock;

    public SmartMeterValidationService(MeterValidationProperties properties,
                                       MeterReadingRepository meterReadingRepository,
                                       AnomalyDetectionService anomalyDetectionService,
                                       Clock clock) {
        this.properties = properties;
        this.meterReadingRepository = meterReadingRepository;
        this.anomalyDetectionService = anomalyDetectionService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ReadingValidationResult validate(User household, MeterReadingSubmission reading) {
        List<RuleFinding> findings = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        // A. Required fields
        if (household == null || household.getId() == null) {
            findings.add(missing("householdCode", "a known household is required"));
        }
        if (reading.readingId() == null || reading.readingId().isBlank()) {
            findings.add(missing("readingId", "readingId is required"));
        } else if (!READING_ID.matcher(reading.readingId()).matches()) {
            findings.add(new RuleFinding(AnomalyRule.INVALID_IDENTIFIER, "readingId",
                    "readingId must be 1-64 characters: letters, digits, '.', '_', ':' or '-'"));
        }
        if (reading.sequenceNo() == null) {
            findings.add(missing("sequenceNo", "sequenceNo is required"));
        } else if (reading.sequenceNo() <= 0) {
            findings.add(new RuleFinding(AnomalyRule.INVALID_IDENTIFIER, "sequenceNo", "sequenceNo must be a positive integer"));
        }
        if (reading.readingTime() == null) {
            findings.add(missing("readingTime", "readingTime is required"));
        }
        if (reading.energyGeneratedKwh() == null) {
            findings.add(missing("energyGeneratedKwh", "energyGeneratedKwh is required"));
        }
        if (reading.energyConsumedKwh() == null) {
            findings.add(missing("energyConsumedKwh", "energyConsumedKwh is required"));
        }

        // B. Numeric rules
        findings.addAll(anomalyDetectionService.checkQuantity("energyGeneratedKwh", reading.energyGeneratedKwh(), true, properties.maxScale()));
        findings.addAll(anomalyDetectionService.checkQuantity("energyConsumedKwh", reading.energyConsumedKwh(), true, properties.maxScale()));

        // C. Plausibility
        checkMaximum("energyGeneratedKwh", reading.energyGeneratedKwh(), properties.maxGeneratedKwh()).ifPresent(findings::add);
        checkMaximum("energyConsumedKwh", reading.energyConsumedKwh(), properties.maxConsumedKwh()).ifPresent(findings::add);
        LocalDateTime now = LocalDateTime.now(clock);
        if (reading.readingTime() != null) {
            if (reading.readingTime().isAfter(now.plus(properties.maxFutureSkew()))) {
                findings.add(new RuleFinding(AnomalyRule.FUTURE_TIMESTAMP, "readingTime",
                        "readingTime is more than " + properties.maxFutureSkew().toMinutes() + " minutes in the future"));
            } else if (reading.readingTime().isBefore(now.minus(properties.maxReadingAge()))) {
                findings.add(new RuleFinding(AnomalyRule.STALE_TIMESTAMP, "readingTime",
                        "readingTime is older than the maximum accepted age of " + describe(properties.maxReadingAge())));
            }
        }

        BigDecimal available = reading.energyGeneratedKwh() != null && reading.energyConsumedKwh() != null
                ? reading.energyGeneratedKwh().subtract(reading.energyConsumedKwh()) : null;

        boolean identityKnown = household != null && household.getId() != null
                && reading.readingId() != null && !reading.readingId().isBlank() && reading.sequenceNo() != null;
        if (!identityKnown) {
            return ReadingValidationResult.of(findings, notes, available);
        }

        // D. Duplicate / replay
        List<RuleFinding> duplicates = anomalyDetectionService.checkDuplicateReading(household.getId(), reading.readingId(), reading.sequenceNo());
        findings.addAll(duplicates);

        // E. Continuity with previous readings
        Optional<MeterReading> latest = meterReadingRepository.findTopByHouseholdIdOrderBySequenceNoDesc(household.getId());
        if (latest.isEmpty()) {
            notes.add("First reading for this household: continuity and sudden-change checks were skipped");
        } else {
            MeterReading previous = latest.get();
            boolean duplicateSequence = duplicates.stream().anyMatch(f -> f.rule() == AnomalyRule.DUPLICATE_SEQUENCE);
            if (!duplicateSequence && reading.sequenceNo() < previous.getSequenceNo()) {
                findings.add(new RuleFinding(AnomalyRule.STALE_SEQUENCE, "sequenceNo",
                        "sequenceNo " + reading.sequenceNo() + " is lower than the latest stored sequence number "
                                + previous.getSequenceNo() + " (stale or replayed reading)"));
            }
            if (reading.readingTime() != null && !reading.readingTime().isAfter(previous.getReadingTime())) {
                findings.add(new RuleFinding(AnomalyRule.STALE_TIMESTAMP, "readingTime",
                        "readingTime is not later than the previous reading (stale or replayed reading)"));
            }
            long missing = reading.sequenceNo() - previous.getSequenceNo() - 1;
            if (missing > properties.maxSequenceGap()) {
                findings.add(new RuleFinding(AnomalyRule.SEQUENCE_GAP, "sequenceNo",
                        missing + " sequence number(s) missing since the latest reading " + previous.getSequenceNo()
                                + " (tolerance " + properties.maxSequenceGap() + "); flagged for review"));
            }
        }
        checkSuddenChanges(household, reading, notes).forEach(findings::add);

        // Excessive activity
        anomalyDetectionService.checkReadingActivity(household.getId()).ifPresent(findings::add);

        return ReadingValidationResult.of(findings, notes, available);
    }

    /** Sudden-change checks against the last VALID reading inside the continuity window. */
    private List<RuleFinding> checkSuddenChanges(User household, MeterReadingSubmission reading, List<String> notes) {
        List<RuleFinding> findings = new ArrayList<>();
        if (reading.readingTime() == null || reading.energyGeneratedKwh() == null || reading.energyConsumedKwh() == null) {
            return findings;
        }
        Optional<MeterReading> lastValid = meterReadingRepository
                .findTopByHouseholdIdAndValidationStatusOrderBySequenceNoDesc(household.getId(), ReadingValidationStatus.VALID);
        if (lastValid.isEmpty()) {
            notes.add("No earlier validated reading: sudden-change checks were skipped");
            return findings;
        }
        MeterReading previous = lastValid.get();
        Duration elapsed = Duration.between(previous.getReadingTime(), reading.readingTime());
        if (elapsed.compareTo(properties.continuityWindow()) > 0) {
            notes.add("Last validated reading is older than " + describe(properties.continuityWindow())
                    + ": sudden-change checks were skipped (history treated as missing)");
            return findings;
        }
        anomalyDetectionService.checkSuddenChange("energyGeneratedKwh",
                EnergyValues.toBigDecimal(previous.getEnergyGeneratedKwh()), reading.energyGeneratedKwh()).ifPresent(findings::add);
        anomalyDetectionService.checkSuddenChange("energyConsumedKwh",
                EnergyValues.toBigDecimal(previous.getEnergyConsumedKwh()), reading.energyConsumedKwh()).ifPresent(findings::add);
        return findings;
    }

    private static Optional<RuleFinding> checkMaximum(String field, BigDecimal value, BigDecimal maximum) {
        if (value != null && value.compareTo(maximum) > 0) {
            return Optional.of(new RuleFinding(AnomalyRule.IMPLAUSIBLE_READING, field,
                    field + " " + value.toPlainString() + " exceeds the plausible maximum of "
                            + maximum.stripTrailingZeros().toPlainString() + " kWh per reading"));
        }
        return Optional.empty();
    }

    private static RuleFinding missing(String field, String message) {
        return new RuleFinding(AnomalyRule.MISSING_REQUIRED_FIELD, field, message);
    }

    private static String describe(Duration duration) {
        return duration.toHours() >= 24 && duration.toHours() % 24 == 0
                ? duration.toDays() + " day(s)" : duration.toMinutes() + " minute(s)";
    }
}
