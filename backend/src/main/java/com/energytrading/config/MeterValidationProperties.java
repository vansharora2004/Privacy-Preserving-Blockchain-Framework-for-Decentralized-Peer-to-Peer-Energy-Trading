package com.energytrading.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Smart-meter validation limits ({@code app.meter.*}). Validated at startup so a
 * misconfigured threshold stops the application instead of silently weakening checks.
 *
 * @param maxGeneratedKwh   largest plausible energy generated in a single reading
 * @param maxConsumedKwh    largest plausible energy consumed in a single reading
 * @param maxScale          maximum decimal places accepted for energy values (3 = Wh resolution)
 * @param maxFutureSkew     how far a reading timestamp may lie in the future (clock skew tolerance)
 * @param maxReadingAge     readings older than this are rejected as stale
 * @param maxSequenceGap    number of missing sequence numbers tolerated before a reading is flagged
 * @param continuityWindow  previous validated readings older than this are not used for change checks
 */
@Validated
@ConfigurationProperties(prefix = "app.meter")
public record MeterValidationProperties(
        @NotNull @Positive @DefaultValue("25.000") BigDecimal maxGeneratedKwh,
        @NotNull @Positive @DefaultValue("25.000") BigDecimal maxConsumedKwh,
        @PositiveOrZero @Max(6) @DefaultValue("3") int maxScale,
        @NotNull @DefaultValue("5m") Duration maxFutureSkew,
        @NotNull @DefaultValue("7d") Duration maxReadingAge,
        @PositiveOrZero @DefaultValue("0") long maxSequenceGap,
        @NotNull @DefaultValue("24h") Duration continuityWindow) {

    public MeterValidationProperties {
        if (maxFutureSkew != null && maxFutureSkew.isNegative()) {
            throw new IllegalArgumentException("app.meter.max-future-skew must not be negative");
        }
        if (maxReadingAge != null && (maxReadingAge.isNegative() || maxReadingAge.isZero())) {
            throw new IllegalArgumentException("app.meter.max-reading-age must be positive");
        }
        if (continuityWindow != null && (continuityWindow.isNegative() || continuityWindow.isZero())) {
            throw new IllegalArgumentException("app.meter.continuity-window must be positive");
        }
    }
}
