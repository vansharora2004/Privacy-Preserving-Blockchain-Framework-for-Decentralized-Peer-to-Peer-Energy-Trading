package com.energytrading.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Marketplace rules ({@code app.marketplace.*}), validated at startup.
 *
 * @param defaultOfferLifetime  lifetime when the seller does not specify one
 * @param minOfferLifetime      shortest allowed offer lifetime
 * @param maxOfferLifetime      longest allowed offer lifetime
 * @param maxSourceReadingAge   an offer must be backed by a VALID reading at most this old
 * @param reservationTimeout    PENDING trades not submitted within this time expire (reservation released)
 * @param flaggedReviewTimeout  FLAGGED trades not reviewed within this time expire (reservation released)
 * @param schedulerEnabled      runs offer expiry, reservation expiry and blockchain reconciliation
 */
@Validated
@ConfigurationProperties(prefix = "app.marketplace")
public record MarketplaceProperties(
        @NotNull @DefaultValue("60m") Duration defaultOfferLifetime,
        @NotNull @DefaultValue("5m") Duration minOfferLifetime,
        @NotNull @DefaultValue("24h") Duration maxOfferLifetime,
        @NotNull @DefaultValue("24h") Duration maxSourceReadingAge,
        @NotNull @DefaultValue("15m") Duration reservationTimeout,
        @NotNull @DefaultValue("24h") Duration flaggedReviewTimeout,
        @DefaultValue("true") boolean schedulerEnabled) {

    public MarketplaceProperties {
        for (Duration d : new Duration[]{defaultOfferLifetime, minOfferLifetime, maxOfferLifetime,
                maxSourceReadingAge, reservationTimeout, flaggedReviewTimeout}) {
            if (d != null && (d.isNegative() || d.isZero())) {
                throw new IllegalArgumentException("app.marketplace durations must be positive");
            }
        }
        if (minOfferLifetime != null && maxOfferLifetime != null && defaultOfferLifetime != null
                && (minOfferLifetime.compareTo(maxOfferLifetime) > 0
                    || defaultOfferLifetime.compareTo(minOfferLifetime) < 0
                    || defaultOfferLifetime.compareTo(maxOfferLifetime) > 0)) {
            throw new IllegalArgumentException("app.marketplace offer lifetimes must satisfy min <= default <= max");
        }
    }
}
