package com.energytrading.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({MeterValidationProperties.class, AnomalyProperties.class, PrivacyProperties.class,
        MarketplaceProperties.class, BlockchainProperties.class})
public class AppConfig {

    /**
     * System-default-zone clock, matching the {@code LocalDateTime.now()} used by the entity
     * lifecycle callbacks. Injected so that time-based rules can be tested deterministically.
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
