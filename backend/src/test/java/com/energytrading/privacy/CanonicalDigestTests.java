package com.energytrading.privacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalDigestTests {

    private final DigestService digests = new DigestService();
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 17, 9, 30, 15, 123_456_000);

    private String readingDigest(String generated, String consumed, String available, LocalDateTime time) {
        return digests.meterReadingDigest("HH-001", "MR-1", 7L, new BigDecimal(generated), new BigDecimal(consumed),
                new BigDecimal(available), time);
    }

    @Test
    @DisplayName("Identical canonical records produce identical digests")
    void identicalRecordsSameDigest() {
        assertThat(readingDigest("8.5", "3.5", "5.0", TIME)).isEqualTo(readingDigest("8.5", "3.5", "5.0", TIME));
    }

    @Test
    @DisplayName("Numeric representation is canonical: 5, 5.0, 5.000 and Double 5.0 are the same value")
    void numericRepresentationIsCanonical() {
        String a = digests.sha256Hex(CanonicalRecord.of("t", 1).field("x", new BigDecimal("5")));
        String b = digests.sha256Hex(CanonicalRecord.of("t", 1).field("x", new BigDecimal("5.000")));
        String c = digests.sha256Hex(CanonicalRecord.of("t", 1).field("x", 5.0d));
        assertThat(a).isEqualTo(b).isEqualTo(c);
        assertThat(CanonicalRecord.of("t", 1).field("x", new BigDecimal("1E+3")).toCanonicalString()).isEqualTo("t/v1|x=1000");
    }

    @Test
    @DisplayName("Any changed field changes the digest")
    void changedRecordDifferentDigest() {
        String original = readingDigest("8.5", "3.5", "5.0", TIME);
        assertThat(readingDigest("8.6", "3.5", "5.1", TIME)).isNotEqualTo(original);
        assertThat(readingDigest("8.5", "3.5", "5.0", TIME.plusNanos(1_000))).isNotEqualTo(original);
        assertThat(digests.meterReadingDigest("HH-002", "MR-1", 7L, new BigDecimal("8.5"), new BigDecimal("3.5"),
                new BigDecimal("5.0"), TIME)).isNotEqualTo(original);
    }

    @Test
    @DisplayName("Timestamps are truncated to microseconds (DATETIME(6)) and use a fixed format")
    void timestampCanonicalization() {
        assertThat(readingDigest("1", "1", "0", TIME.plusNanos(999))).isEqualTo(readingDigest("1", "1", "0", TIME));
        assertThat(CanonicalRecord.of("t", 1).field("at", LocalDateTime.of(2026, 1, 2, 3, 4)).toCanonicalString())
                .isEqualTo("t/v1|at=2026-01-02T03:04:00.000000");
    }

    @Test
    @DisplayName("Strings are length-prefixed: separator characters cannot shift field boundaries")
    void separatorInjectionIsUnambiguous() {
        String one = CanonicalRecord.of("t", 1).field("a", "x|b=y").field("b", "z").toCanonicalString();
        String two = CanonicalRecord.of("t", 1).field("a", "x").field("b", "y|b=z").toCanonicalString();
        assertThat(one).isNotEqualTo(two);
        assertThat(CanonicalRecord.of("t", 1).field("a", "x|b=y").toCanonicalString()).isEqualTo("t/v1|a=5:x|b=y");
    }

    @Test
    @DisplayName("Null is distinct from any string, including the null marker itself")
    void nullHandling() {
        String nullValue = CanonicalRecord.of("t", 1).field("a", (String) null).toCanonicalString();
        String tilde = CanonicalRecord.of("t", 1).field("a", "~").toCanonicalString();
        String empty = CanonicalRecord.of("t", 1).field("a", "").toCanonicalString();
        assertThat(nullValue).isEqualTo("t/v1|a=~");
        assertThat(tilde).isEqualTo("t/v1|a=1:~");
        assertThat(empty).isEqualTo("t/v1|a=0:");
    }

    @Test
    @DisplayName("Unicode is NFC-normalized and UTF-8 encoded")
    void unicodeNormalization() {
        String composed = CanonicalRecord.of("t", 1).field("a", "café").toCanonicalString();
        String decomposed = CanonicalRecord.of("t", 1).field("a", "café").toCanonicalString();
        assertThat(composed).isEqualTo(decomposed).isEqualTo("t/v1|a=5:café");
    }

    @Test
    @DisplayName("Field order is part of the format; invalid input is rejected")
    void orderAndInvalidInput() {
        String ab = CanonicalRecord.of("t", 1).field("a", 1L).field("b", 2L).toCanonicalString();
        String ba = CanonicalRecord.of("t", 1).field("b", 2L).field("a", 1L).toCanonicalString();
        assertThat(ab).isNotEqualTo(ba);
        assertThatThrownBy(() -> CanonicalRecord.of("t", 1).field("x", Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalRecord.of("t", 1).field("x", Double.POSITIVE_INFINITY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalRecord.of("t", 1).field("a", 1L).field("a", 2L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalRecord.of("t", 1).field("a|b", 1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("The meter-reading digest is SHA-256 of the documented canonical string")
    void meterReadingCanonicalForm() throws Exception {
        CanonicalRecord record = digests.meterReadingRecord("HH-001", "MR-1", 7L, new BigDecimal("8.50"),
                new BigDecimal("3.5"), new BigDecimal("5.0"), TIME);
        String expectedCanonical = "meter-reading/v1|householdCode=6:HH-001|readingId=4:MR-1|sequenceNo=7"
                + "|energyGeneratedKwh=8.5|energyConsumedKwh=3.5|availableKwh=5|readingTime=2026-10-17T09:30:15.123456";
        assertThat(record.toCanonicalString()).isEqualTo(expectedCanonical);
        String independent = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(expectedCanonical.getBytes(StandardCharsets.UTF_8)));
        assertThat(digests.sha256Hex(record)).isEqualTo(independent).hasSize(64);
    }

    @Test
    @DisplayName("Legacy Batch 1 seed digests can still be recomputed")
    void legacyDigest() throws Exception {
        String independent = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("HH-001|MR-HH001-0001|1|8.5|3.5|5.0".getBytes(StandardCharsets.UTF_8)));
        assertThat(digests.legacyMeterReadingDigest("HH-001", "MR-HH001-0001", 1L, 8.5, 3.5, 5.0)).isEqualTo(independent);
    }

    @Test
    @DisplayName("Digest comparison is case-insensitive and null-safe")
    void matches() {
        String digest = readingDigest("1", "0", "1", TIME);
        assertThat(digests.matches(digest, digest.toUpperCase())).isTrue();
        assertThat(digests.matches(digest, readingDigest("2", "0", "2", TIME))).isFalse();
        assertThat(digests.matches(null, digest)).isFalse();
    }
}
