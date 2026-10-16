package com.energytrading.service;

import com.energytrading.config.AnomalyProperties;
import com.energytrading.config.PrivacyProperties;
import com.energytrading.dto.FindingView;
import com.energytrading.dto.OfferCheckResponse;
import com.energytrading.model.AnomalyLog;
import com.energytrading.model.User;
import com.energytrading.model.enums.AnomalyReviewStatus;
import com.energytrading.model.enums.AnomalySeverity;
import com.energytrading.model.enums.Role;
import com.energytrading.privacy.PseudonymService;
import com.energytrading.repository.AnomalyLogRepository;
import com.energytrading.repository.MeterReadingRepository;
import com.energytrading.service.anomaly.AnomalyRule;
import com.energytrading.service.anomaly.RuleFinding;
import com.energytrading.service.anomaly.RuleOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AnomalyDetectionService}: every rule, configuration and log contents. */
class AnomalyDetectionServiceTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T06:00:00Z"), ZoneOffset.UTC);

    private MeterReadingRepository readings;
    private AnomalyLogRepository anomalyLogs;
    private AuditService audit;
    private PseudonymService pseudonyms;
    private User seller;

    @BeforeEach
    void setUp() {
        readings = mock(MeterReadingRepository.class);
        anomalyLogs = mock(AnomalyLogRepository.class);
        when(anomalyLogs.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        audit = mock(AuditService.class);
        pseudonyms = new PseudonymService(new PrivacyProperties("anomaly-test-key-0123456789abcdefghij"));
        seller = new User("HH-SELLER", "Sunny Roof", "Asha Private", "asha@private.example", "hash",
                Role.ROLE_HOUSEHOLD, BigDecimal.TEN, 5.0, 0.0);
        seller.setId(42L);
    }

    private AnomalyDetectionService service(String maxPrice, String maxJump, int maxReadings) {
        AnomalyProperties properties = new AnomalyProperties(new BigDecimal(maxPrice), new BigDecimal(maxJump),
                new BigDecimal("3.0"), new BigDecimal("1.0"), Duration.ofHours(1), maxReadings, 10);
        return new AnomalyDetectionService(properties, readings, anomalyLogs, pseudonyms, audit, CLOCK);
    }

    private AnomalyDetectionService defaults() {
        return service("15.00", "20.00", 12);
    }

    private static List<AnomalyRule> rules(OfferCheckResponse response) {
        return response.findings().stream().map(f -> AnomalyRule.valueOf(f.ruleCode())).toList();
    }

    @Test
    @DisplayName("1. A normal offer produces no anomaly and no log entry")
    void normalOperation() {
        OfferCheckResponse response = defaults().evaluateProposedOffer(seller, new BigDecimal("2.5"), new BigDecimal("6.00"), 3);
        assertThat(response.outcome()).isEqualTo("PASSED");
        assertThat(response.findings()).isEmpty();
        assertThat(response.validatedSurplusKwh()).isEqualByComparingTo("5.0");
        ArgumentCaptor<List<AnomalyLog>> saved = ArgumentCaptor.captor();
        verify(anomalyLogs).saveAll(saved.capture());
        assertThat(saved.getValue()).isEmpty();
    }

    @Test
    @DisplayName("2. Overselling: 50 kWh against a validated 5 kWh surplus is REJECTED with an explicit reason and audited")
    void overselling() {
        OfferCheckResponse response = defaults().evaluateProposedOffer(seller, new BigDecimal("50"), new BigDecimal("6.00"), 3);
        assertThat(response.outcome()).isEqualTo("REJECTED");
        FindingView finding = response.findings().get(0);
        assertThat(finding.ruleCode()).isEqualTo("OVERSELLING");
        assertThat(finding.message()).contains("50 kWh exceeds the validated available surplus of 5 kWh");
        verify(audit).record(eq("HH-SELLER"), eq("OFFER_PRECHECK"), eq("REJECTED"), any());
    }

    @Test
    @DisplayName("Overselling with no validated surplus treats availability as zero")
    void oversellingWithoutSurplus() {
        assertThat(defaults().checkOverselling(new BigDecimal("0.1"), null)).isPresent();
        assertThat(defaults().checkOverselling(new BigDecimal("5"), new BigDecimal("5.0"))).isEmpty();
    }

    @Test
    @DisplayName("3. A price above the configured maximum is FLAGGED for review (not called fraud)")
    void excessivePrice() {
        OfferCheckResponse response = defaults().evaluateProposedOffer(seller, new BigDecimal("1"), new BigDecimal("100.00"), 3);
        assertThat(response.outcome()).isEqualTo("FLAGGED");
        FindingView finding = response.findings().get(0);
        assertThat(finding.ruleCode()).isEqualTo("SUSPICIOUS_PRICE");
        assertThat(finding.outcome()).isEqualTo("FLAGGED");
        assertThat(finding.message()).contains("100.00 exceeds the configured maximum of 15.00").contains("flagged for review")
                .doesNotContainIgnoringCase("fraud");
    }

    @Test
    @DisplayName("7. Invalid quantities and prices are REJECTED with explanations")
    void invalidQuantities() {
        OfferCheckResponse zero = defaults().evaluateProposedOffer(seller, BigDecimal.ZERO, new BigDecimal("5.00"), 3);
        assertThat(zero.outcome()).isEqualTo("REJECTED");
        assertThat(zero.findings().get(0).message()).contains("greater than zero");

        OfferCheckResponse negative = defaults().evaluateProposedOffer(seller, new BigDecimal("-2"), new BigDecimal("-1"), 3);
        assertThat(rules(negative)).containsExactlyInAnyOrder(AnomalyRule.INVALID_QUANTITY, AnomalyRule.INVALID_PRICE);

        OfferCheckResponse tooPrecise = defaults().evaluateProposedOffer(seller, new BigDecimal("1.0001"), new BigDecimal("5.001"), 3);
        assertThat(rules(tooPrecise)).containsOnly(AnomalyRule.INVALID_PRECISION);
    }

    @Test
    @DisplayName("4. Sudden reading changes are detected (absolute and relative)")
    void suddenChange() {
        assertThat(defaults().checkSuddenChange("energyGeneratedKwh", new BigDecimal("1"), new BigDecimal("22")))
                .map(RuleFinding::rule).contains(AnomalyRule.SUDDEN_READING_CHANGE);
        assertThat(defaults().checkSuddenChange("energyGeneratedKwh", new BigDecimal("2"), new BigDecimal("9")))
                .map(RuleFinding::message).hasValueSatisfying(m -> assertThat(m).contains("350%"));
        assertThat(defaults().checkSuddenChange("energyGeneratedKwh", new BigDecimal("2"), new BigDecimal("3"))).isEmpty();
    }

    @Test
    @DisplayName("10. Missing history and zero denominators do not crash")
    void missingHistory() {
        assertThat(defaults().checkSuddenChange("x", null, new BigDecimal("5"))).isEmpty();
        assertThat(defaults().checkSuddenChange("x", BigDecimal.ZERO, new BigDecimal("5"))).isEmpty();
        assertThat(defaults().checkReadingActivity(null)).isEmpty();
        assertThat(defaults().checkDuplicateReading(null, null, null)).isEmpty();
    }

    @Test
    @DisplayName("5. Excessive activity in the window is FLAGGED using a COUNT query")
    void excessiveActivity() {
        when(readings.countByHouseholdIdAndCreatedAtAfter(eq(42L), any())).thenReturn(3L);
        assertThat(service("15", "20", 3).checkReadingActivity(42L)).map(RuleFinding::rule).contains(AnomalyRule.EXCESSIVE_ACTIVITY);
        assertThat(service("15", "20", 4).checkReadingActivity(42L)).isEmpty();
    }

    @Test
    @DisplayName("6. Duplicate reading IDs and sequence numbers are detected")
    void duplicates() {
        when(readings.existsByReadingId("R-1")).thenReturn(true);
        when(readings.existsByHouseholdIdAndSequenceNo(42L, 9L)).thenReturn(true);
        assertThat(defaults().checkDuplicateReading(42L, "R-1", 9L)).extracting(RuleFinding::rule)
                .containsExactly(AnomalyRule.DUPLICATE_READING_ID, AnomalyRule.DUPLICATE_SEQUENCE);
    }

    @Test
    @DisplayName("9. Threshold configuration changes rule behaviour")
    void configurationChangesBehaviour() {
        assertThat(service("150.00", "20", 12).checkPrice(new BigDecimal("100.00"))).isEmpty();
        assertThat(service("15.00", "20", 12).checkPrice(new BigDecimal("100.00"))).isNotEmpty();
        assertThat(service("15", "25", 12).checkSuddenChange("x", new BigDecimal("0.5"), new BigDecimal("22"))).isEmpty();
        assertThat(service("15", "20", 12).checkSuddenChange("x", new BigDecimal("0.5"), new BigDecimal("22"))).isPresent();
    }

    @Test
    @DisplayName("8/11. Anomaly logs carry code, severity, explanation, pseudonymous subject and review status, but no personal data")
    void anomalyLogContents() {
        defaults().evaluateProposedOffer(seller, new BigDecimal("50"), new BigDecimal("100.00"), 3);

        ArgumentCaptor<List<AnomalyLog>> saved = ArgumentCaptor.captor();
        verify(anomalyLogs).saveAll(saved.capture());
        List<AnomalyLog> logs = saved.getValue();
        assertThat(logs).extracting(AnomalyLog::getRuleCode).containsExactlyInAnyOrder("SUSPICIOUS_PRICE", "OVERSELLING");
        String pseudonym = pseudonyms.householdPseudonym("HH-SELLER");
        for (AnomalyLog log : logs) {
            assertThat(log.getEventType()).isEqualTo("OFFER_CHECK");
            assertThat(log.getTargetReferenceId()).isEqualTo(pseudonym);
            assertThat(log.getDescription()).isNotBlank();
            assertThat(log.getReviewStatus()).isEqualTo(AnomalyReviewStatus.DETECTED);
            assertThat(log.getSeverity()).isIn(AnomalySeverity.MEDIUM, AnomalySeverity.HIGH);
            assertThat(log.getTargetReferenceId() + log.getDescription())
                    .doesNotContain("HH-SELLER", "Sunny Roof", "Asha Private", "asha@private.example");
        }
    }

    @Test
    @DisplayName("Every rule has a stable code, a severity and an outcome")
    void ruleCatalogue() {
        for (AnomalyRule rule : AnomalyRule.values()) {
            assertThat(rule.code()).isEqualTo(rule.name());
            assertThat(rule.severity()).isNotNull();
            assertThat(AnomalyRule.fromCode(rule.code())).contains(rule);
        }
        assertThat(AnomalyRule.OVERSELLING.outcome()).isEqualTo(RuleOutcome.REJECTED);
        assertThat(AnomalyRule.SUSPICIOUS_PRICE.outcome()).isEqualTo(RuleOutcome.FLAGGED);
        assertThat(AnomalyRule.fromCode("NOT_A_RULE")).isEmpty();
    }
}
