package com.energytrading.service;

import com.energytrading.dto.TradeRequest;
import com.energytrading.dto.TradeResult;
import com.energytrading.model.TradeTransaction;
import com.energytrading.repository.TradeTransactionRepository;
import com.energytrading.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Java-to-contract trade against a running Hardhat node (skipped unless
 * RUN_BLOCKCHAIN_IT=true). Uses its own in-memory database and the real Web3j gateway.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:livechain;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL;LOCK_TIMEOUT=10000",
        "app.blockchain.enabled=true",
        "app.blockchain.rpc-url=${BLOCKCHAIN_RPC_URL:http://127.0.0.1:8545}",
        "app.blockchain.chain-id=${BLOCKCHAIN_CHAIN_ID:31337}",
        "app.blockchain.contract-address=${CONTRACT_ADDRESS:}",
        "app.blockchain.deployment-id=${BLOCKCHAIN_DEPLOYMENT_ID:}",
        "app.blockchain.signer-private-key=${BLOCKCHAIN_SIGNER_PRIVATE_KEY:}"
})
@EnabledIfEnvironmentVariable(named = "RUN_BLOCKCHAIN_IT", matches = "true")
class LiveBlockchainTradeIT {

    @Autowired private TradingService tradingService;
    @Autowired private TradeSettlementService settlementService;
    @Autowired private MarketplaceService marketplaceService;
    @Autowired private MeterReadingService meterReadingService;
    @Autowired private UserRepository userRepository;
    @Autowired private TradeTransactionRepository tradeRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("Offer -> trade -> transaction mined on Hardhat -> CONFIRMED with hash, block and matching on-chain record")
    void tradeIsRecordedOnChain() throws Exception {
        assertThat(settlementService.status().ok()).as(settlementService.status().problem()).isTrue();

        MarketplaceFixtures fx = new MarketplaceFixtures(userRepository, passwordEncoder, meterReadingService, marketplaceService);
        String seller = fx.household("LSELL", "0.00");
        String buyer = fx.household("LBUY", "100.00");
        fx.validReading(seller, "8.0", "3.0");
        String offerId = fx.offer(seller, "4", "6.50");

        String key = "live-" + UUID.randomUUID();
        TradeResult result = tradingService.requestTrade(buyer, new TradeRequest(offerId, new BigDecimal("2.5")), key);

        assertThat(result.outcome()).isEqualTo("CONFIRMED");
        TradeTransaction t = tradeRepository.findByTradeId(result.trade().tradeId()).orElseThrow();
        assertThat(t.getTxHash()).matches("0x[0-9a-f]{64}");
        assertThat(t.getBlockNumber()).isPositive();
        assertThat(t.getChainId()).isEqualTo(31337L);
        assertThat(t.getContractAddress()).isEqualToIgnoringCase(System.getenv("CONTRACT_ADDRESS"));
        assertThat(t.getTotalAmount()).isEqualByComparingTo("16.25");
        assertThat(settlementService.verifyOnChain(t.getTradeId())).isEqualTo("MATCH");

        // A retry with the same Idempotency-Key returns the same trade and sends nothing new.
        TradeResult retry = tradingService.requestTrade(buyer, new TradeRequest(offerId, new BigDecimal("2.5")), key);
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.trade().blockchain().txHash()).isEqualTo(t.getTxHash());
        assertThat(settlementService.submit(t.getTradeId())).isEqualTo(TradeSettlementService.Outcome.SKIPPED);
    }
}
