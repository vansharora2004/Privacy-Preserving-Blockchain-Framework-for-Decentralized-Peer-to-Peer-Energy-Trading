package com.energytrading.blockchain;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Provides the deterministic fake chain as the application's BlockchainGateway. */
@TestConfiguration
public class FakeChainConfig {

    @Bean
    FakeBlockchainGateway fakeBlockchainGateway() {
        return new FakeBlockchainGateway();
    }
}
