package com.energytrading.config;

import com.energytrading.blockchain.BlockchainGateway;
import com.energytrading.blockchain.Web3jBlockchainGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the Web3j gateway only when {@code BLOCKCHAIN_ENABLED=true}. Invalid or missing settings
 * then stop the application at startup with a message naming the variable (never its value).
 */
@Configuration
public class BlockchainConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.blockchain.enabled", havingValue = "true")
    public BlockchainGateway blockchainGateway(BlockchainProperties properties) {
        return new Web3jBlockchainGateway(properties);
    }
}
