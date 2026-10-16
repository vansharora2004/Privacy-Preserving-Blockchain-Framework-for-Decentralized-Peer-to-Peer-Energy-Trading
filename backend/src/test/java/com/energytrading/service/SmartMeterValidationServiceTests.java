package com.energytrading.service;

import com.energytrading.config.AnomalyProperties;
import com.energytrading.config.MeterValidationProperties;
import com.energytrading.dto.MeterReadingSubmission;
import com.energytrading.model.MeterReading;
import com.energytrading.model.User;
import com.energytrading.model.enums.ReadingValidationStatus;
import com.energytrading.model.enums.Role;
import com.energytrading.privacy.PseudonymService;
import com.energytrading.repository.AnomalyLogRepository;
import com.energytrading.repository.MeterReadingRepository;
import com.energytrading.service.anomaly.AnomalyRule;
import com.energytrading.service.anomaly.RuleFinding;
import com.energytrading.service.anomaly.RuleOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for {@link SmartMeterValidationService} with a mocked repository and a fixed clock. */
class SmartMeterValidationServiceTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T06:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    private MeterReadingRepository repository;
    private User household;
    private SmartMeterValidationService service;

    @BeforeEach
    void setUp() {
        repository = mock(MeterReadingRepository.class);
        household = new User("HH-T1", "Test", null, null, "hash", Role.ROLE_HOUSEHOLD, BigDecimal.ZERO, 0.0, 0.0);
        household.setId(1L);
        service = service(meterProperties(0), anomalyProperties("20.00"));
    }

    private SmartMeterValidationService service(MeterValidationProperties meter, AnomalyProperties anomaly) {
        AnomalyDetectionService anomalyService = new AnomalyDetectionService(anomaly, repository,
                mock(AnomalyLogRepository.class), mock(PseudonymService.class), mock(AuditService.class), CLOCK);
        return new SmartMeterValidationService(meter, repository, anomalyService, CLOCK);
    }

    static MeterValidationProperties meterProperties(long maxGap) {
        return new MeterValidationProperties(new BigDecimal("25"), new BigDecimal("25"), 3,
                Duration.ofMinutes(5), Duration.ofDays(7), maxGap, Duration.ofHours(24));
    }

    static AnomalyProperties anomalyProperties(String maxJump) {
        return new AnomalyProperties(new BigDecimal("15.00"), new BigDecimal(maxJump), new BigDecimal("3.0"),
                new BigDecimal("1.0"), Duration.ofHours(1), 12, 10);
    }

    private static MeterReadingSubmission reading(String id, Long seq, String generated, String consumed, LocalDateTime time) {
        return new MeterReadingSubmission(null, id, seq, generated == null ? null : new BigDecimal(generated),
                consumed == null ? null : new BigDecimal(consumed), time);
    }

    private MeterReading previous(long seq, double generated, double consumed, LocalDateTime time, ReadingValidationStatus status) {
        MeterReading r = new MeterReading(household, "PREV-" + seq, seq, generated, consumed, generated - consumed, time, status, null);
        when(repository.findTopByHouseholdIdOrderBySequenceNoDesc(1L)).thenReturn(Optional.of(r));
        if (status == ReadingValidationStatus.VALID) {
            when(repository.findTopByHouseholdIdAndValidationStatusOrderBySequenceNoDesc(1L, ReadingValidationStatus.VALID))
                    .thenReturn(Optional.of(r));
        }
        return r;
    }

    private static Optional<RuleFinding> finding(ReadingValidationResult result, AnomalyRule rule) {
        return result.findings().stream().filter(f -> f.rule() == rule).findFirst();
    }

    @Test
    @DisplayName("1/10. A valid first reading is VALID, with an explanatory note about missing history")
    void validFirstReading() {
        ReadingValidationResult result = service.validate(household, reading("R-1", 1L, "8.5", "3.5", NOW.minusMinutes(1)));

        assertThat(result.status()).isEqualTo(ReadingValidationStatus.VALID);
        assertThat(result.findings()).isEmpty();
        assertThat(result.notes()).anyMatch(n -> n.contains("First reading"));
        assertThat(result.availableKwh()).isEqualByComparingTo("5.0");
        assertThat(result.sellableSurplusKwh()).isEqualByComparingTo("5.0");
    }

    @Test
    @DisplayName("A valid follow-up reading within limits is VALID")
    void validFollowUpReading() {
        previous(4, 8.0, 3.0, NOW.minusHours(1), ReadingValidationStatus.VALID);
        ReadingValidationResult result = service.validate(household, reading("R-5", 5L, "9.0", "3.5", NOW));
        assertThat(result.status()).isEqualTo(ReadingValidationStatus.VALID);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    @DisplayName("2. Negative energy values are REJECTED with a field-specific reason")
    void negativeValues() {
        ReadingValidationResult result = service.validate(household, reading("R-1", 1L, "-1.0", "2", NOW));

        assertThat(result.status()).isEqualTo(ReadingValidationStatus.REJECTED);
        RuleFinding f = finding(result, AnomalyRule.INVALID_QUANTITY).orElseThrow();
        assertThat(f.field()).isEqualTo("energyGeneratedKwh");
        assertThat(f.message()).contains("zero or greater").contains("-1.0");
        assertThat(f.outcome()).isEqualTo(RuleOutcome.REJECTED);
    }

    @Test
    @DisplayName("3. Missing required fields are REJECTED, one finding per missing field")
    void missingFields() {
        ReadingValidationResult result = service.validate(household, reading(null, null, null, null, null));

        assertThat(result.status()).isEqualTo(ReadingValidationStatus.REJECTED);
        assertThat(result.findings()).filteredOn(f -> f.rule() == AnomalyRule.MISSING_REQUIRED_FIELD)
                .extracting(RuleFinding::field)
                .containsExactlyInAnyOrder("readingId", "sequenceNo", "readingTime", "energyGeneratedKwh", "energyConsumedKwh");
        assertThat(result.findings()).allMatch(f -> f.message().contains("required"));

        ReadingValidationResult noHousehold = service.validate(null, reading("R-1", 1L, "1", "1", NOW));
        assertThat(finding(noHousehold, AnomalyRule.MISSING_REQUIRED_FIELD).orElseThrow().field()).isEqualTo("householdCode");
    }

    @Test
    @DisplayName("Malformed identifiers and non-positive sequence numbers are REJECTED")
    void invalidIdentifiers() {
        ReadingValidationResult result = service.validate(household, reading("bad id with spaces", 0L, "1", "1", NOW));
        assertThat(result.findings()).filteredOn(f -> f.rule() == AnomalyRule.INVALID_IDENTIFIER)
                .extracting(RuleFinding::field).containsExactlyInAnyOrder("readingId", "sequenceNo");
    }

    @Test
    @DisplayName("4. Implausible values above the configured maximum are REJECTED, not modified")
    void implausibleValues() {
        ReadingValidationResult result = service.validate(household, reading("R-1", 1L, "50", "2", NOW));

        assertThat(result.status()).isEqualTo(ReadingValidationStatus.REJECTED);
        RuleFinding f = finding(result, AnomalyRule.IMPLAUSIBLE_READING).orElseThrow();
        assertThat(f.message()).contains("50").contains("plausible maximum of 25");
        assertThat(result.availableKwh()).isEqualByComparingTo("48");
    }

    @Test
    @DisplayName("Values finer than the configured precision are REJECTED instead of rounded")
    void precision() {
        ReadingValidationResult result = service.validate(household, reading("R-1", 1L, "1.0005", "0", NOW));
        assertThat(finding(result, AnomalyRule.INVALID_PRECISION).orElseThrow().message()).contains("not rounded");
        assertThat(service.validate(household, reading("R-1", 1L, "1.500000", "0", NOW)).status())
                .isEqualTo(ReadingValidationStatus.VALID);
    }

    @Test
    @DisplayName("5. A reused reading ID is REJECTED as a possible replay")
    void duplicateReadingId() {
        when(repository.existsByReadingId("R-1")).thenReturn(true);
        ReadingValidationResult result = service.validate(household, reading("R-1", 1L, "1", "1", NOW));

        assertThat(result.status()).isEqualTo(ReadingValidationStatus.REJECTED);
        assertThat(finding(result, AnomalyRule.DUPLICATE_READING_ID).orElseThrow().message()).contains("already been used");
    }

    @Test
    @DisplayName("6. A reused household/sequence number is REJECTED")
    void duplicateSequence() {
        previous(3, 1.0, 1.0, NOW.minusHours(1), ReadingValidationStatus.VALID);
        when(repository.existsByHouseholdIdAndSequenceNo(1L, 3L)).thenReturn(true);
        ReadingValidationResult result = service.validate(household, reading("R-NEW", 3L, "1", "1", NOW));

        assertThat(result.status()).isEqualTo(ReadingValidationStatus.REJECTED);
        assertThat(finding(result, AnomalyRule.DUPLICATE_SEQUENCE).orElseThrow().message()).contains("sequenceNo 3");
        assertThat(finding(result, AnomalyRule.STALE_SEQUENCE)).isEmpty();
    }

    @Test
    @DisplayName("7. Replayed or stale readings are REJECTED: older sequence, non-increasing or too-old timestamp")
    void staleReadings() {
        previous(10, 1.0, 1.0, NOW.minusMinutes(30), ReadingValidationStatus.VALID);

        ReadingValidationResult olderSequence = service.validate(household, reading("R-OLD", 5L, "1", "1", NOW));
        assertThat(finding(olderSequence, AnomalyRule.STALE_SEQUENCE).orElseThrow().message()).contains("lower than the latest");

        ReadingValidationResult sameTime = service.validate(household, reading("R-11", 11L, "1", "1", NOW.minusMinutes(30)));
        assertThat(sameTime.status()).isEqualTo(ReadingValidationStatus.REJECTED);
        assertThat(finding(sameTime, AnomalyRule.STALE_TIMESTAMP).orElseThrow().message()).contains("not later than the previous");

        ReadingValidationResult tooOld = service.validate(household, reading("R-12", 12L, "1", "1", NOW.minusDays(8)));
        assertThat(finding(tooOld, AnomalyRule.STALE_TIMESTAMP).orElseThrow().message()).contains("maximum accepted age");

        ReadingValidationResult future = service.validate(household, reading("R-13", 13L, "1", "1", NOW.plusHours(1)));
        assertThat(finding(future, AnomalyRule.FUTURE_TIMESTAMP).orElseThrow().outcome()).isEqualTo(RuleOutcome.REJECTED);
    }

    @Test
    @DisplayName("8. A sequence gap is FLAGGED (not rejected), and the tolerance is configurable")
    void sequenceGap() {
        previous(3, 1.0, 1.0, NOW.minusHours(1), ReadingValidationStatus.VALID);
        ReadingValidationResult result = service.validate(household, reading("R-6", 6L, "1", "1", NOW));

        assertThat(result.status()).isEqualTo(ReadingValidationStatus.FLAGGED);
        assertThat(finding(result, AnomalyRule.SEQUENCE_GAP).orElseThrow().message()).contains("2 sequence number(s) missing");

        ReadingValidationResult tolerant = service(meterProperties(5), anomalyProperties("20.00"))
                .validate(household, reading("R-6", 6L, "1", "1", NOW));
        assertThat(tolerant.status()).isEqualTo(ReadingValidationStatus.VALID);
    }

    @Test
    @DisplayName("9. Sudden absolute and relative changes are FLAGGED with the measured change")
    void suddenChanges() {
        previous(1, 1.0, 1.0, NOW.minusHours(1), ReadingValidationStatus.VALID);
        ReadingValidationResult absolute = service.validate(household, reading("R-2", 2L, "22", "1", NOW));
        assertThat(absolute.status()).isEqualTo(ReadingValidationStatus.FLAGGED);
        assertThat(finding(absolute, AnomalyRule.SUDDEN_READING_CHANGE).orElseThrow().message())
                .contains("changed by 21 kWh").contains("limit 20 kWh");

        previous(1, 2.0, 1.0, NOW.minusHours(1), ReadingValidationStatus.VALID);
        ReadingValidationResult relative = service.validate(household, reading("R-2", 2L, "9", "1", NOW));
        assertThat(finding(relative, AnomalyRule.SUDDEN_READING_CHANGE).orElseThrow().message())
                .contains("350%").contains("limit 300%");
    }

    @Test
    @DisplayName("Sudden-change checks ignore stale history and zero baselines instead of failing")
    void suddenChangeEdgeCases() {
        previous(1, 0.0, 1.0, NOW.minusHours(1), ReadingValidationStatus.VALID);
        assertThat(service.validate(household, reading("R-2", 2L, "5", "1", NOW)).status())
                .as("zero baseline: relative rule skipped, absolute change within limit")
                .isEqualTo(ReadingValidationStatus.VALID);

        previous(1, 1.0, 1.0, NOW.minusDays(2), ReadingValidationStatus.VALID);
        ReadingValidationResult stale = service.validate(household, reading("R-2", 2L, "24", "1", NOW));
        assertThat(stale.status()).isEqualTo(ReadingValidationStatus.VALID);
        assertThat(stale.notes()).anyMatch(n -> n.contains("history treated as missing"));
    }

    @Test
    @DisplayName("Change detection compares with the last VALID reading, not a FLAGGED one")
    void flaggedReadingNotUsedAsBaseline() {
        previous(1, 20.0, 1.0, NOW.minusHours(1), ReadingValidationStatus.FLAGGED);
        ReadingValidationResult result = service.validate(household, reading("R-2", 2L, "1", "1", NOW));
        assertThat(result.notes()).anyMatch(n -> n.contains("No earlier validated reading"));
        assertThat(result.status()).isEqualTo(ReadingValidationStatus.VALID);
    }

    @Test
    @DisplayName("Threshold configuration changes rule behaviour")
    void thresholdConfiguration() {
        // Baseline 0.5 kWh is below the relative-check minimum, so only the absolute limit applies.
        previous(1, 0.5, 1.0, NOW.minusHours(1), ReadingValidationStatus.VALID);
        MeterReadingSubmission jump = reading("R-2", 2L, "22", "1", NOW);
        assertThat(service.validate(household, jump).status()).as("default limit 20 kWh").isEqualTo(ReadingValidationStatus.FLAGGED);
        SmartMeterValidationService lenient = service(meterProperties(0), anomalyProperties("24.00"));
        assertThat(lenient.validate(household, jump).status()).as("configured limit 24 kWh").isEqualTo(ReadingValidationStatus.VALID);
    }

    @Test
    @DisplayName("Excessive activity in the configured window is FLAGGED")
    void excessiveActivity() {
        when(repository.countByHouseholdIdAndCreatedAtAfter(eq(1L), any())).thenReturn(12L);
        ReadingValidationResult result = service.validate(household, reading("R-1", 1L, "1", "1", NOW));
        assertThat(result.status()).isEqualTo(ReadingValidationStatus.FLAGGED);
        assertThat(finding(result, AnomalyRule.EXCESSIVE_ACTIVITY).orElseThrow().message()).contains("reading 13 within 60 minutes");
    }

    @Test
    @DisplayName("Consumed energy is never available for sale: a deficit gives zero sellable surplus")
    void energyAvailability() {
        ReadingValidationResult result = service.validate(household, reading("R-1", 1L, "1.0", "4.0", NOW));
        assertThat(result.availableKwh()).isEqualByComparingTo("-3.0");
        assertThat(result.sellableSurplusKwh()).isEqualByComparingTo("0");
    }

    @Nested
    class OutcomeCombination {
        @Test
        @DisplayName("A hard failure wins over flags: REJECTED, with every reason reported")
        void rejectionWinsOverFlags() {
            previous(1, 1.0, 1.0, NOW.minusHours(1), ReadingValidationStatus.VALID);
            when(repository.countByHouseholdIdAndCreatedAtAfter(anyLong(), any())).thenReturn(100L);
            ReadingValidationResult result = service.validate(household, reading("R-9", 9L, "-1", "1", NOW));
            assertThat(result.status()).isEqualTo(ReadingValidationStatus.REJECTED);
            assertThat(result.findings()).extracting(RuleFinding::rule)
                    .contains(AnomalyRule.INVALID_QUANTITY, AnomalyRule.SEQUENCE_GAP, AnomalyRule.EXCESSIVE_ACTIVITY);
        }
    }
}
