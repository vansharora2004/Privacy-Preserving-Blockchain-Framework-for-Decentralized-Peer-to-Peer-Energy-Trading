package com.energytrading.privacy;

import com.energytrading.config.PrivacyProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Stable pseudonyms for public and log views: {@code hh-} + the first 20 hex characters of
 * HMAC-SHA-256(key, "household|" + householdCode).
 * <p>
 * A keyed HMAC is used instead of a plain hash so that nobody without the key can recompute
 * a pseudonym from a guessed household code. Pseudonyms are stable, so records carrying the
 * same pseudonym can still be correlated with each other: this is pseudonymity, not anonymity.
 * Changing the key changes every pseudonym; references stored earlier (for example in anomaly
 * logs) then no longer match the current views.
 */
@Service
public class PseudonymService {

    static final int MIN_KEY_LENGTH = 32;
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public PseudonymService(PrivacyProperties properties) {
        String secret = properties.pseudonymKey();
        if (secret == null || secret.isBlank() || secret.length() < MIN_KEY_LENGTH) {
            // Fail safely: never fall back to a default key, and never echo the configured value.
            throw new IllegalStateException("PRIVACY_PSEUDONYM_KEY (app.privacy.pseudonym-key) must be set to a secret of at least "
                    + MIN_KEY_LENGTH + " characters");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String householdPseudonym(String householdCode) {
        if (householdCode == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] tag = mac.doFinal(("household|" + householdCode).getBytes(StandardCharsets.UTF_8));
            return "hh-" + HexFormat.of().formatHex(tag).substring(0, 20);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 not available", e);
        }
    }

    /**
     * Per-trade party reference for the blockchain: HMAC-SHA-256(key, "trade-party|" + tradeId + "|"
     * + role + "|" + householdCode), 32 bytes. Because the trade ID is part of the input, the same
     * household gets a different reference in every trade, so on-chain records cannot be linked
     * to each other without the key.
     */
    public byte[] tradePartyReference(String tradeId, String role, String householdCode) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(("trade-party|" + tradeId + "|" + role + "|" + householdCode).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 not available", e);
        }
    }

    /**
     * Fingerprint of the active key (HMAC of a fixed label), stored with each trade's party references
     * so that verification can tell which key produced them. It does not reveal the key.
     */
    public String keyId() {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return "k-" + HexFormat.of().formatHex(mac.doFinal("key-id".getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 not available", e);
        }
    }

    @Override
    public String toString() {
        return "PseudonymService[key=<redacted>]";
    }
}
