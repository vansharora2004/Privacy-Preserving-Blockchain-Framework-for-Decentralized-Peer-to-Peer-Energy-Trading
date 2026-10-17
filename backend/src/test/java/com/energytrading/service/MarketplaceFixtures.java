package com.energytrading.service;

import com.energytrading.dto.CreateOfferRequest;
import com.energytrading.dto.MeterReadingSubmission;
import com.energytrading.dto.OfferResult;
import com.energytrading.model.User;
import com.energytrading.model.enums.Role;
import com.energytrading.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Creates test households, validated readings and offers through the real services. */
class MarketplaceFixtures {

    static final String PASSWORD = "it-market-pass";

    private final UserRepository userRepository;
    private final MeterReadingService meterReadingService;
    private final MarketplaceService marketplaceService;
    private final String passwordHash;
    private final Map<String, Long> sequences = new ConcurrentHashMap<>();

    MarketplaceFixtures(UserRepository userRepository, PasswordEncoder passwordEncoder,
                        MeterReadingService meterReadingService, MarketplaceService marketplaceService) {
        this.userRepository = userRepository;
        this.meterReadingService = meterReadingService;
        this.marketplaceService = marketplaceService;
        this.passwordHash = passwordEncoder.encode(PASSWORD);
    }

    String household(String prefix, String balance) {
        String code = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        userRepository.save(new User(code, code + " alias", "Private " + prefix, code.toLowerCase() + "@market.example",
                passwordHash, Role.ROLE_HOUSEHOLD, new BigDecimal(balance), 0.0, 0.0));
        return code;
    }

    /** Submits a reading through SmartMeterValidationService and asserts it is VALID. */
    void validReading(String code, String generated, String consumed) {
        long seq = sequences.merge(code, 1L, Long::sum);
        LocalDateTime time = LocalDateTime.now().minusMinutes(90).plusMinutes(seq).truncatedTo(ChronoUnit.SECONDS);
        var response = meterReadingService.submit(code, new MeterReadingSubmission(null, code + "-R" + seq, seq,
                new BigDecimal(generated), new BigDecimal(consumed), time));
        assertThat(response.status()).as("fixture reading must be VALID").isEqualTo("VALID");
    }

    /** A seller with a validated surplus and one ACTIVE offer; returns the offer ID. */
    String offer(String sellerCode, String quantity, String price) {
        OfferResult result = marketplaceService.createOffer(sellerCode,
                new CreateOfferRequest(new BigDecimal(quantity), new BigDecimal(price), null), null);
        assertThat(result.offer()).as("fixture offer must be created").isNotNull();
        return result.offer().offerId();
    }

    User user(String code) {
        return userRepository.findByHouseholdCode(code).orElseThrow();
    }
}
