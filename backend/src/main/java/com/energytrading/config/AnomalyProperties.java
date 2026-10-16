package com.energytrading.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Anomaly-rule thresholds ({@code app.anomaly.*}), validated at startup.
 *
 * @param maxPricePerKwh               prices above this are flagged for review
 * @param maxReadingJumpKwh            absolute change from the last validated reading that is flagged
 * @param maxRelativeChange            relative change (3.0 = 300%) from the last validated reading that is flagged
 * @param relativeChangeMinBaselineKwh relative checks are skipped below this baseline (avoids division by ~0)
 * @param activityWindow               time window for the excessive-activity rule
 * @param maxReadingsPerWindow         readings per household allowed inside {@code activityWindow}
 * @param maxTradesPerHour             reserved for the Batch 3 trade-frequency rule
 */
@Validated
@ConfigurationProperties(prefix = "app.anomaly")
public record AnomalyProperties(
        @NotNull @Positive @DefaultValue("15.00") BigDecimal maxPricePerKwh,
        @NotNull @Positive @DefaultValue("20.00") BigDecimal maxReadingJumpKwh,
        @NotNull @Positive @DefaultValue("3.0") BigDecimal maxRelativeChange,
        @NotNull @PositiveOrZero @DefaultValue("1.0") BigDecimal relativeChangeMinBaselineKwh,
        @NotNull @DefaultValue("1h") Duration activityWindow,
        @Positive @DefaultValue("12") int maxReadingsPerWindow,
        @Positive @DefaultValue("10") int maxTradesPerHour) {

    public AnomalyProperties {
        if (activityWindow != null && (activityWindow.isNegative() || activityWindow.isZero())) {
            throw new IllegalArgumentException("app.anomaly.activity-window must be positive");
        }
    }
}
