package com.energytrading.privacy;

import com.energytrading.config.PrivacyProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PseudonymServiceTests {

    private static final String KEY_A = "unit-test-key-A-0123456789abcdefghijkl";
    private static final String KEY_B = "unit-test-key-B-0123456789abcdefghijkl";

    private final PseudonymService service = new PseudonymService(new PrivacyProperties(KEY_A));

    @Test
    @DisplayName("Pseudonyms are stable, distinct per household and do not reveal the household code")
    void stableAndDistinct() {
        String first = service.householdPseudonym("HH-001");
        assertThat(first).isEqualTo(service.householdPseudonym("HH-001"));
        assertThat(first).isNotEqualTo(service.householdPseudonym("HH-002"));
        assertThat(first).matches("hh-[0-9a-f]{20}").doesNotContain("HH-001").doesNotContain("001");
    }

    @Test
    @DisplayName("Pseudonyms depend on the secret key (keyed HMAC, not a public hash)")
    void dependsOnKey() {
        PseudonymService other = new PseudonymService(new PrivacyProperties(KEY_B));
        assertThat(other.householdPseudonym("HH-001")).isNotEqualTo(service.householdPseudonym("HH-001"));
    }

    @Test
    @DisplayName("A missing key fails startup instead of falling back to an insecure default")
    void missingKeyFails() {
        assertThatThrownBy(() -> new PseudonymService(new PrivacyProperties(null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PRIVACY_PSEUDONYM_KEY");
        assertThatThrownBy(() -> new PseudonymService(new PrivacyProperties("   ")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("A short key is rejected and its value is never echoed")
    void shortKeyNotEchoed() {
        String weak = "short-secret-value";
        assertThatThrownBy(() -> new PseudonymService(new PrivacyProperties(weak)))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(weak));
    }

    @Test
    @DisplayName("The key is redacted from toString output")
    void keyRedacted() {
        assertThat(new PrivacyProperties(KEY_A).toString()).doesNotContain(KEY_A).contains("redacted");
        assertThat(service.toString()).doesNotContain(KEY_A);
    }
}
