package com.energytrading.service;

import com.energytrading.dto.FindingView;
import com.energytrading.dto.MeterReadingSubmission;
import com.energytrading.dto.MeterReadingSubmissionResponse;
import com.energytrading.exception.ResourceNotFoundException;
import com.energytrading.model.MeterReading;
import com.energytrading.model.User;
import com.energytrading.model.enums.ReadingValidationStatus;
import com.energytrading.privacy.DigestService;
import com.energytrading.privacy.PseudonymService;
import com.energytrading.repository.MeterReadingRepository;
import com.energytrading.repository.UserRepository;
import com.energytrading.service.anomaly.AnomalyRule;
import com.energytrading.service.anomaly.RuleFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;

/**
 * Submission workflow for meter readings: validate, store VALID and FLAGGED readings, record
 * anomaly and audit entries, and return the outcome.
 * <ul>
 *   <li>REJECTED readings are never stored (the audit and anomaly logs keep the evidence).</li>
 *   <li>FLAGGED readings are stored for review but do not update the household's surplus.</li>
 *   <li>VALID readings update the household's validated surplus/deficit.</li>
 * </ul>
 * The database unique constraints on {@code reading_id} and {@code (household_id, sequence_no)}
 * are the final safeguard: if a concurrent submission wins the race after validation passed,
 * the violation is reported as {@link AnomalyRule#CONCURRENT_DUPLICATE} instead of an error.
 */
@Service
public class MeterReadingService {

    private static final Logger log = LoggerFactory.getLogger(MeterReadingService.class);
    private static final int MAX_REVIEW_NOTE = 255;

    private final UserRepository userRepository;
    private final MeterReadingRepository meterReadingRepository;
    private final SmartMeterValidationService validationService;
    private final AnomalyDetectionService anomalyDetectionService;
    private final AuditService auditService;
    private final DigestService digestService;
    private final PseudonymService pseudonymService;
    private final TransactionTemplate transactionTemplate;

    public MeterReadingService(UserRepository userRepository,
                               MeterReadingRepository meterReadingRepository,
                               SmartMeterValidationService validationService,
                               AnomalyDetectionService anomalyDetectionService,
                               AuditService auditService,
                               DigestService digestService,
                               PseudonymService pseudonymService,
                               PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.meterReadingRepository = meterReadingRepository;
        this.validationService = validationService;
        this.anomalyDetectionService = anomalyDetectionService;
        this.auditService = auditService;
        this.digestService = digestService;
        this.pseudonymService = pseudonymService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * @param submitterCode household code of the authenticated household
     * @throws AccessDeniedException if the submission names a different household
     */
    public MeterReadingSubmissionResponse submit(String submitterCode, MeterReadingSubmission submission) {
        if (submission.householdCode() != null && !submission.householdCode().equals(submitterCode)) {
            auditService.record(submitterCode, "METER_READING_SUBMIT", "DENIED", "attempted submission for another household");
            throw new AccessDeniedException("Households may only submit readings for their own meter");
        }
        User household = userRepository.findByHouseholdCode(submitterCode)
                .orElseThrow(() -> new ResourceNotFoundException("Household not found"));

        ReadingValidationResult validated = validationService.validate(household, submission);
        ReadingValidationResult result = validated;
        String recordHash = null;
        if (!result.isRejected()) {
            try {
                recordHash = transactionTemplate.execute(tx -> persist(household.getId(), submission, validated));
            } catch (DataIntegrityViolationException e) {
                // Lost a race with a concurrent duplicate submission; the constraint held.
                result = result.withFinding(new RuleFinding(AnomalyRule.CONCURRENT_DUPLICATE, "readingId",
                        "A concurrent submission with the same readingId or sequenceNo was stored first; this reading was rejected"));
                recordHash = null;
            }
        }

        String pseudonym = pseudonymService.householdPseudonym(household.getHouseholdCode());
        String recordLabel = submission.sequenceNo() != null ? "sequence " + submission.sequenceNo() : null;
        anomalyDetectionService.record("METER_READING", pseudonym, recordLabel, result.findings());
        auditService.record(submitterCode, "METER_READING_SUBMIT", result.status().name(),
                "sequenceNo=" + submission.sequenceNo() + " rules=" + AnomalyDetectionService.codes(result.findings()));
        log.info("Meter reading from {} -> {}", pseudonym, result.status());

        // Only a VALID reading establishes sellable surplus; flagged or rejected values are not reported as sellable.
        boolean valid = result.status() == ReadingValidationStatus.VALID;
        return new MeterReadingSubmissionResponse(submission.readingId(), pseudonym, result.status().name(),
                !result.isRejected(), result.isRejected() ? null : result.availableKwh(),
                valid ? result.sellableSurplusKwh() : null, recordHash,
                result.findings().stream().map(FindingView::from).toList(), result.notes());
    }

    private String persist(Long householdId, MeterReadingSubmission submission, ReadingValidationResult result) {
        User household = userRepository.findById(householdId)
                .orElseThrow(() -> new ResourceNotFoundException("Household not found"));
        // Truncate to the DATETIME(6) precision so the stored value and the digest input agree.
        LocalDateTime readingTime = submission.readingTime().truncatedTo(ChronoUnit.MICROS);
        String recordHash = digestService.meterReadingDigest(household.getHouseholdCode(), submission.readingId(),
                submission.sequenceNo(), submission.energyGeneratedKwh(), submission.energyConsumedKwh(),
                result.availableKwh(), readingTime);

        MeterReading reading = new MeterReading(household, submission.readingId(), submission.sequenceNo(),
                EnergyValues.toDouble(submission.energyGeneratedKwh()), EnergyValues.toDouble(submission.energyConsumedKwh()),
                EnergyValues.toDouble(result.availableKwh()), readingTime, result.status(), recordHash);
        if (result.status() == ReadingValidationStatus.FLAGGED) {
            reading.setRejectionReason(reviewNote(result));
        }
        meterReadingRepository.saveAndFlush(reading);

        if (result.status() == ReadingValidationStatus.VALID) {
            household.setCurrentSurplusKwh(EnergyValues.toDouble(result.sellableSurplusKwh()));
            household.setCurrentDeficitKwh(EnergyValues.toDouble(result.availableKwh().negate().max(BigDecimal.ZERO)));
            userRepository.save(household);
        }
        return recordHash;
    }

    private static String reviewNote(ReadingValidationResult result) {
        String note = "Flagged for review: " + result.findings().stream().map(RuleFinding::ruleCode)
                .distinct().collect(Collectors.joining(", "));
        return note.length() > MAX_REVIEW_NOTE ? note.substring(0, MAX_REVIEW_NOTE) : note;
    }
}
