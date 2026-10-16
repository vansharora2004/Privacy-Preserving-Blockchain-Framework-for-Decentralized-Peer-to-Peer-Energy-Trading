package com.energytrading.privacy;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * SHA-256 digests of canonical records, for integrity checks.
 * <p>
 * A digest detects that a stored record changed after the digest was computed. It does
 * <b>not</b> provide confidentiality (inputs such as meter values are predictable and can be
 * brute-forced, so a digest is not anonymous), and it does not prove the data was truthful
 * when recorded. Anyone who can change both a record and its stored digest can defeat a
 * purely local check.
 */
@Service
public class DigestService {

    public static final String METER_READING_V1 = "CANONICAL_SHA256_V1";
    /** Format used by the Batch 1 seed data: {@code code|readingId|seq|gen|cons|avail}. */
    public static final String METER_READING_LEGACY_V0 = "LEGACY_PIPE_SHA256_V0";

    public String sha256Hex(CanonicalRecord record) {
        return sha256Hex(record.toBytes());
    }

    /**
     * Canonical form of a meter reading. The validation status is intentionally excluded:
     * it can change during review, while the digest protects the measurement itself.
     */
    public CanonicalRecord meterReadingRecord(String householdCode, String readingId, Long sequenceNo,
                                              BigDecimal energyGeneratedKwh, BigDecimal energyConsumedKwh,
                                              BigDecimal availableKwh, LocalDateTime readingTime) {
        return CanonicalRecord.of("meter-reading", 1)
                .field("householdCode", householdCode)
                .field("readingId", readingId)
                .field("sequenceNo", sequenceNo)
                .field("energyGeneratedKwh", energyGeneratedKwh)
                .field("energyConsumedKwh", energyConsumedKwh)
                .field("availableKwh", availableKwh)
                .field("readingTime", readingTime);
    }

    public String meterReadingDigest(String householdCode, String readingId, Long sequenceNo,
                                     BigDecimal energyGeneratedKwh, BigDecimal energyConsumedKwh,
                                     BigDecimal availableKwh, LocalDateTime readingTime) {
        return sha256Hex(meterReadingRecord(householdCode, readingId, sequenceNo,
                energyGeneratedKwh, energyConsumedKwh, availableKwh, readingTime));
    }

    /** Recomputes the Batch 1 seed-data digest so pre-existing rows can still be verified. */
    public String legacyMeterReadingDigest(String householdCode, String readingId, Long sequenceNo,
                                           Double generated, Double consumed, Double available) {
        return sha256Hex((householdCode + "|" + readingId + "|" + sequenceNo + "|" + generated + "|"
                + consumed + "|" + available).getBytes(StandardCharsets.UTF_8));
    }

    /** Constant-time comparison of two hex digests. */
    public boolean matches(String expectedHex, String actualHex) {
        if (expectedHex == null || actualHex == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedHex.toLowerCase().getBytes(StandardCharsets.US_ASCII),
                actualHex.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    private static String sha256Hex(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
