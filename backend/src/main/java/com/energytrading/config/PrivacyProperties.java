package com.energytrading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Privacy configuration ({@code app.privacy.*}).
 * <p>
 * The pseudonym key is a secret: it is supplied through the {@code PRIVACY_PSEUDONYM_KEY}
 * environment variable, is never logged, and is deliberately excluded from {@link #toString()}.
 * It is checked by {@link com.energytrading.privacy.PseudonymService}, which refuses to start
 * without a sufficiently long key instead of falling back to an insecure default.
 */
@ConfigurationProperties(prefix = "app.privacy")
public record PrivacyProperties(String pseudonymKey) {

    @Override
    public String toString() {
        return "PrivacyProperties[pseudonymKey=<redacted>]";
    }
}
