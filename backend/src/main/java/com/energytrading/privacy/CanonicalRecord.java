package com.energytrading.privacy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Deterministic, versioned text serialization of a record, used as SHA-256 digest input.
 * <p>
 * Unlike JSON, the output never depends on map ordering, whitespace or number formatting:
 * <ul>
 *   <li>Fields appear in the order they are added, which is fixed in code per record type.</li>
 *   <li>Strings are Unicode NFC-normalized and length-prefixed ({@code <utf8-bytes>:<value>}),
 *       so a value containing the separator cannot be confused with another field.</li>
 *   <li>Numbers use {@link BigDecimal} plain notation with trailing zeros removed
 *       ({@code 5}, {@code 5.0} and {@code 5.000} are identical); non-finite doubles are rejected.</li>
 *   <li>Timestamps are truncated to microseconds (the MySQL {@code DATETIME(6)} precision) and
 *       written as {@code yyyy-MM-dd'T'HH:mm:ss.SSSSSS}.</li>
 *   <li>{@code null} is written as {@code ~}, which a non-null value can never produce.</li>
 * </ul>
 * Example: {@code meter-reading/v1|readingId=8:MR-00001|sequenceNo=1|availableKwh=-3}
 */
public final class CanonicalRecord {

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]*");
    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9-]*");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");
    private static final String NULL = "~";

    private final String type;
    private final int version;
    private final Map<String, String> fields = new LinkedHashMap<>();

    private CanonicalRecord(String type, int version) {
        if (type == null || !TYPE.matcher(type).matches() || version < 1) {
            throw new IllegalArgumentException("Invalid record type or version");
        }
        this.type = type;
        this.version = version;
    }

    public static CanonicalRecord of(String type, int version) {
        return new CanonicalRecord(type, version);
    }

    public CanonicalRecord field(String name, String value) {
        return put(name, value == null ? NULL : encodeString(value));
    }

    public CanonicalRecord field(String name, BigDecimal value) {
        return put(name, value == null ? NULL : value.stripTrailingZeros().toPlainString());
    }

    public CanonicalRecord field(String name, Double value) {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite number for field " + name);
        }
        return field(name, value == null ? null : BigDecimal.valueOf(value));
    }

    public CanonicalRecord field(String name, Long value) {
        return put(name, value == null ? NULL : value.toString());
    }

    public CanonicalRecord field(String name, LocalDateTime value) {
        return put(name, value == null ? NULL : TIMESTAMP.format(value.truncatedTo(ChronoUnit.MICROS)));
    }

    public CanonicalRecord field(String name, Enum<?> value) {
        return put(name, value == null ? NULL : value.name());
    }

    /** The canonical string; the digest input is its UTF-8 encoding. */
    public String toCanonicalString() {
        StringBuilder sb = new StringBuilder(type).append("/v").append(version);
        fields.forEach((name, value) -> sb.append('|').append(name).append('=').append(value));
        return sb.toString();
    }

    public byte[] toBytes() {
        return toCanonicalString().getBytes(StandardCharsets.UTF_8);
    }

    private CanonicalRecord put(String name, String encoded) {
        Objects.requireNonNull(name, "field name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid field name: " + name);
        }
        if (fields.putIfAbsent(name, encoded) != null) {
            throw new IllegalArgumentException("Duplicate field: " + name);
        }
        return this;
    }

    private static String encodeString(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        return normalized.getBytes(StandardCharsets.UTF_8).length + ":" + normalized;
    }
}
