package com.energytrading.service;

import com.energytrading.dto.FindingView;
import com.energytrading.dto.IntegrityCheckResponse;
import com.energytrading.dto.IntegrityCheckResponse.IntegrityResult;
import com.energytrading.dto.MeterReadingSubmission;
import com.energytrading.dto.MeterReadingSubmissionResponse;
import com.energytrading.model.AnomalyLog;
import com.energytrading.model.MeterReading;
import com.energytrading.model.User;
import com.energytrading.model.enums.ReadingValidationStatus;
import com.energytrading.model.enums.Role;
import com.energytrading.privacy.DigestService;
import com.energytrading.privacy.PrivacyService;
import com.energytrading.privacy.PseudonymService;
import com.energytrading.repository.AnomalyLogRepository;
import com.energytrading.repository.AuditLogRepository;
import com.energytrading.repository.MeterReadingRepository;
import com.energytrading.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** End-to-end submission workflow against the in-memory database (no MockMvc). */
@SpringBootTest
class MeterReadingSubmissionIntegrationTests {

    @Autowired private MeterReadingService meterReadingService;
    @Autowired private UserRepository userRepository;
    @Autowired private MeterReadingRepository meterReadingRepository;
    @Autowired private AnomalyLogRepository anomalyLogRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private AnomalyDetectionService anomalyDetectionService;
    @Autowired private AuditService auditService;
    @Autowired private DigestService digestService;
    @Autowired private PseudonymService pseudonymService;
    @Autowired private PrivacyService privacyService;
    @Autowired private PlatformTransactionManager transactionManager;

    private String code;
    private LocalDateTime base;

    @BeforeEach
    void setUp() {
        code = "IT-" + UUID.randomUUID().toString().substring(0, 8);
        userRepository.save(new User(code, "IT household", "Private Person", code.toLowerCase() + "@it.example",
                "$2a$10$notusedforlogin", Role.ROLE_HOUSEHOLD, BigDecimal.ZERO, 0.0, 0.0));
        base = LocalDateTime.now().minusHours(2).truncatedTo(ChronoUnit.SECONDS);
    }

    private MeterReadingSubmission reading(String suffix, long seq, String generated, String consumed) {
        return new MeterReadingSubmission(null, code + "-" + suffix, seq, new BigDecimal(generated), new BigDecimal(consumed),
                base.plusMinutes(seq));
    }

    private static Authentication auth(String name, Role role) {
        return new UsernamePasswordAuthenticationToken(name, null, List.of(new SimpleGrantedAuthority(role.name())));
    }

    private User household() {
        return userRepository.findByHouseholdCode(code).orElseThrow();
    }

    private List<AnomalyLog> anomaliesForHousehold() {
        return anomalyLogRepository.findByTargetReferenceId(pseudonymService.householdPseudonym(code));
    }

    @Test
    @DisplayName("A VALID reading is stored with its digest, updates the validated surplus and is audited")
    void validReadingStored() {
        MeterReadingSubmissionResponse response = meterReadingService.submit(code, reading("1", 1, "8.5", "3.5"));

        assertThat(response.status()).isEqualTo("VALID");
        assertThat(response.stored()).isTrue();
        assertThat(response.recordHash()).hasSize(64);
        assertThat(response.householdPseudonym()).isEqualTo(pseudonymService.householdPseudonym(code));

        MeterReading stored = meterReadingRepository.findByReadingId(code + "-1").orElseThrow();
        assertThat(stored.getValidationStatus()).isEqualTo(ReadingValidationStatus.VALID);
        assertThat(stored.getRecordHash()).isEqualTo(response.recordHash());
        assertThat(household().getCurrentSurplusKwh()).isEqualTo(5.0);
        assertThat(household().getCurrentDeficitKwh()).isEqualTo(0.0);
        assertThat(anomaliesForHousehold()).isEmpty();
        assertThat(auditLogRepository.findByActorUsername(code))
                .anyMatch(a -> a.getAction().equals("METER_READING_SUBMIT") && a.getOutcome().equals("VALID"));
    }

    @Test
    @DisplayName("A FLAGGED reading is stored for review but is not treated as validated")
    void flaggedReadingNotValidated() {
        meterReadingService.submit(code, reading("1", 1, "2", "1"));
        MeterReadingSubmissionResponse flagged = meterReadingService.submit(code, reading("3", 3, "9", "1"));

        assertThat(flagged.status()).isEqualTo("FLAGGED");
        assertThat(flagged.stored()).isTrue();
        assertThat(flagged.findings()).extracting(FindingView::ruleCode).contains("SEQUENCE_GAP", "SUDDEN_READING_CHANGE");
        MeterReading stored = meterReadingRepository.findByReadingId(code + "-3").orElseThrow();
        assertThat(stored.getValidationStatus()).isEqualTo(ReadingValidationStatus.FLAGGED);
        assertThat(stored.getRejectionReason()).contains("SEQUENCE_GAP");
        assertThat(household().getCurrentSurplusKwh()).as("surplus still from the VALID reading").isEqualTo(1.0);
        assertThat(anomaliesForHousehold()).extracting(AnomalyLog::getRuleCode).contains("SEQUENCE_GAP");
    }

    @Test
    @DisplayName("A REJECTED reading is not stored; the anomaly log uses a pseudonym and no personal data")
    void rejectedReadingNotStored() {
        MeterReadingSubmissionResponse response = meterReadingService.submit(code, reading("1", 1, "-2", "1"));

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.stored()).isFalse();
        assertThat(response.recordHash()).isNull();
        assertThat(meterReadingRepository.existsByReadingId(code + "-1")).isFalse();
        List<AnomalyLog> logs = anomaliesForHousehold();
        assertThat(logs).extracting(AnomalyLog::getRuleCode).containsExactly("INVALID_QUANTITY");
        assertThat(logs.get(0).getEventType()).isEqualTo("METER_READING");
        assertThat(logs.get(0).getDescription()).contains("[sequence 1]")
                .doesNotContain(code, "Private Person", "@it.example");
        assertThat(auditLogRepository.findByActorUsername(code)).anyMatch(a -> a.getOutcome().equals("REJECTED"));
    }

    @Test
    @DisplayName("A replayed submission is REJECTED by the duplicate checks")
    void replayRejected() {
        MeterReadingSubmission submission = reading("1", 1, "1", "0.5");
        assertThat(meterReadingService.submit(code, submission).status()).isEqualTo("VALID");
        MeterReadingSubmissionResponse replay = meterReadingService.submit(code, submission);
        assertThat(replay.status()).isEqualTo("REJECTED");
        assertThat(replay.findings()).extracting(FindingView::ruleCode).contains("DUPLICATE_READING_ID", "DUPLICATE_SEQUENCE");
    }

    @Test
    @DisplayName("Submitting for another household is denied")
    void otherHouseholdDenied() {
        MeterReadingSubmission forOther = new MeterReadingSubmission("HH-001", code + "-X", 1L, BigDecimal.ONE, BigDecimal.ONE, base);
        assertThatThrownBy(() -> meterReadingService.submit(code, forOther)).isInstanceOf(AccessDeniedException.class);
    }

    /** A service whose validation always passes, so the database constraint is the only safeguard. */
    private MeterReadingService serviceWithoutPrechecks() {
        SmartMeterValidationService alwaysValid = mock(SmartMeterValidationService.class);
        when(alwaysValid.validate(any(), any())).thenReturn(
                ReadingValidationResult.of(List.of(), List.of(), new BigDecimal("1")));
        return new MeterReadingService(userRepository, meterReadingRepository, alwaysValid, anomalyDetectionService,
                auditService, digestService, pseudonymService, transactionManager);
    }

    @Test
    @DisplayName("11. A database uniqueness violation is reported as CONCURRENT_DUPLICATE, not an error")
    void databaseUniquenessViolation() {
        MeterReadingSubmission submission = reading("1", 1, "2", "1");
        assertThat(meterReadingService.submit(code, submission).status()).isEqualTo("VALID");

        MeterReadingSubmissionResponse response = serviceWithoutPrechecks().submit(code, submission);

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.stored()).isFalse();
        assertThat(response.findings()).extracting(FindingView::ruleCode).containsExactly("CONCURRENT_DUPLICATE");
        assertThat(meterReadingRepository.findByHouseholdIdOrderBySequenceNoDesc(household().getId())).hasSize(1);
    }

    @Test
    @DisplayName("12. Concurrent duplicate submissions: exactly one is stored")
    void concurrentDuplicates() throws Exception {
        MeterReadingService racing = serviceWithoutPrechecks();
        MeterReadingSubmission submission = reading("1", 1, "2", "1");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MeterReadingSubmissionResponse>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return racing.submit(code, submission);
            }));
        }
        start.countDown();
        List<String> statuses = new ArrayList<>();
        for (Future<MeterReadingSubmissionResponse> future : futures) {
            statuses.add(future.get(30, TimeUnit.SECONDS).status());
        }
        pool.shutdown();

        assertThat(statuses).containsOnlyOnce("VALID");
        assertThat(statuses).filteredOn("REJECTED"::equals).hasSize(3);
        assertThat(meterReadingRepository.findByHouseholdIdOrderBySequenceNoDesc(household().getId())).hasSize(1);
    }

    @Test
    @DisplayName("Integrity: MATCH for an untouched reading, MISMATCH after tampering, legacy and missing digests handled")
    void integrityVerification() {
        meterReadingService.submit(code, reading("1", 1, "8.5", "3.5"));
        Authentication owner = auth(code, Role.ROLE_HOUSEHOLD);

        IntegrityCheckResponse ok = privacyService.verifyReadingIntegrity(owner, code + "-1");
        assertThat(ok.result()).isEqualTo(IntegrityResult.MATCH);
        assertThat(ok.digestFormat()).isEqualTo(DigestService.METER_READING_V1);
        assertThat(ok.note()).contains("does not prove");

        MeterReading tampered = meterReadingRepository.findByReadingId(code + "-1").orElseThrow();
        tampered.setEnergyGeneratedKwh(18.5);
        meterReadingRepository.save(tampered);
        assertThat(privacyService.verifyReadingIntegrity(owner, code + "-1").result()).isEqualTo(IntegrityResult.MISMATCH);

        User household = household();
        String legacy = digestService.legacyMeterReadingDigest(code, code + "-L", 5L, 8.5, 3.5, 5.0);
        meterReadingRepository.save(new MeterReading(household, code + "-L", 5L, 8.5, 3.5, 5.0, base,
                ReadingValidationStatus.VALID, legacy));
        IntegrityCheckResponse legacyCheck = privacyService.verifyReadingIntegrity(owner, code + "-L");
        assertThat(legacyCheck.result()).isEqualTo(IntegrityResult.MATCH);
        assertThat(legacyCheck.digestFormat()).isEqualTo(DigestService.METER_READING_LEGACY_V0);

        meterReadingRepository.save(new MeterReading(household, code + "-N", 6L, 1.0, 1.0, 0.0, base,
                ReadingValidationStatus.VALID, null));
        assertThat(privacyService.verifyReadingIntegrity(owner, code + "-N").result()).isEqualTo(IntegrityResult.NO_STORED_DIGEST);
    }

    @Test
    @DisplayName("Integrity checks are allowed for owner, admin and auditor, and denied to other households")
    void integrityAccess() {
        meterReadingService.submit(code, reading("1", 1, "1", "1"));
        String id = code + "-1";
        assertThat(privacyService.verifyReadingIntegrity(auth("HH-ADMIN", Role.ROLE_ADMIN), id).result()).isEqualTo(IntegrityResult.MATCH);
        assertThat(privacyService.verifyReadingIntegrity(auth("AUD", Role.ROLE_AUDITOR), id).result()).isEqualTo(IntegrityResult.MATCH);
        assertThatThrownBy(() -> privacyService.verifyReadingIntegrity(auth("HH-002", Role.ROLE_HOUSEHOLD), id))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(auditLogRepository.findByActorUsername("HH-002")).anyMatch(a -> a.getAction().equals("ACCESS_DENIED"));
    }
}
