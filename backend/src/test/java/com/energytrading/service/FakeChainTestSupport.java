package com.energytrading.service;

import com.energytrading.blockchain.BlockchainGateway;
import com.energytrading.blockchain.FakeBlockchainGateway;
import com.energytrading.blockchain.FakeChainConfig;
import com.energytrading.blockchain.TradeChainMapper;
import com.energytrading.dto.TradeRequest;
import com.energytrading.dto.TradeResult;
import com.energytrading.model.EnergyOffer;
import com.energytrading.model.TradeTransaction;
import com.energytrading.model.enums.TradeStatus;
import com.energytrading.repository.AuditLogRepository;
import com.energytrading.repository.BlockchainAlertRepository;
import com.energytrading.repository.EnergyOfferRepository;
import com.energytrading.repository.TradeEventRepository;
import com.energytrading.repository.TradeTransactionRepository;
import com.energytrading.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared context (own in-memory database) and helpers for the fake-chain remediation tests. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:recoverytest;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL;LOCK_TIMEOUT=10000",
        "app.blockchain.receipt-timeout=1s",
        "app.blockchain.receipt-poll-interval=100ms",
        "app.blockchain.unknown-transaction-grace=60s",
        "app.blockchain.max-submission-attempts=3",
        "app.blockchain.submit-lock-wait=300ms",
        "app.blockchain.signer-lease-ttl=30s"
})
@Import(FakeChainConfig.class)
abstract class FakeChainTestSupport {

    @Autowired protected FakeBlockchainGateway chain;
    @Autowired protected TradingService tradingService;
    @Autowired protected TradeSettlementService settlement;
    @Autowired protected TradeRecoveryService recovery;
    @Autowired protected MarketplaceService marketplaceService;
    @Autowired protected MeterReadingService meterReadingService;
    @Autowired protected UserRepository userRepository;
    @Autowired protected EnergyOfferRepository offerRepository;
    @Autowired protected TradeTransactionRepository tradeRepository;
    @Autowired protected TradeEventRepository eventRepository;
    @Autowired protected AuditLogRepository auditLogRepository;
    @Autowired protected BlockchainAlertRepository alertRepository;
    @Autowired protected TradeChainMapper chainMapper;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;

    protected MarketplaceFixtures fx;
    protected String seller;
    protected String buyer;
    protected String offerId;

    @BeforeEach
    void baseSetUp() {
        chain.reset();
        settlement.invalidateVerification();
        jdbc.update("DELETE FROM blockchain_signer_lease");
        fx = new MarketplaceFixtures(userRepository, passwordEncoder, meterReadingService, marketplaceService);
        seller = fx.household("RSELL", "0.00");
        buyer = fx.household("RBUY", "100.00");
        fx.validReading(seller, "8.0", "3.0");
        offerId = fx.offer(seller, "5", "5.00");
    }

    protected TradeResult buy(String quantity) {
        return tradingService.requestTrade(buyer, new TradeRequest(offerId, new BigDecimal(quantity)), "key-" + UUID.randomUUID());
    }

    /** A trade created while the RPC is down, so it stays PENDING for the test to drive. */
    protected String pendingTrade(String quantity) {
        chain.rpcDown = true;
        TradeResult result = buy(quantity);
        chain.rpcDown = false;
        assertThat(result.outcome()).isEqualTo("PENDING");
        return result.trade().tradeId();
    }

    /** A trade whose transaction was accepted but not yet mined. */
    protected String submittedTrade(String quantity) {
        chain.mode = FakeBlockchainGateway.Mode.PENDING_FOREVER;
        String tradeId = buy(quantity).trade().tradeId();
        chain.mode = FakeBlockchainGateway.Mode.SUCCESS;
        assertThat(trade(tradeId).getStatus()).isEqualTo(TradeStatus.SUBMITTED);
        return tradeId;
    }

    protected String confirmedTrade(String quantity) {
        TradeResult result = buy(quantity);
        assertThat(result.outcome()).isEqualTo("CONFIRMED");
        return result.trade().tradeId();
    }

    protected TradeTransaction trade(String tradeId) {
        return tradeRepository.findByTradeId(tradeId).orElseThrow();
    }

    protected BigDecimal balance(String code) {
        return userRepository.findByHouseholdCode(code).orElseThrow().getBalance();
    }

    protected double remaining() {
        return offerRepository.findByOfferId(offerId).map(EnergyOffer::getRemainingKwh).orElseThrow();
    }

    protected List<String> history(String tradeId) {
        return eventRepository.findByTradeIdOrderByIdAsc(trade(tradeId).getId()).stream().map(e -> e.getToStatus().name()).toList();
    }

    protected BlockchainGateway.TradeRecordPayload payload(String tradeId) {
        return new TransactionTemplate(transactionManager).execute(tx -> {
            TradeTransaction t = trade(tradeId);
            return chainMapper.payload(t, t.getSeller().getHouseholdCode(), t.getBuyer().getHouseholdCode());
        });
    }

    protected void ageSubmission(String tradeId) {
        jdbc.update("UPDATE trade_transactions SET submitted_at = ? WHERE trade_id = ?", LocalDateTime.now().minusMinutes(5), tradeId);
    }

    protected void ageReservation(String tradeId) {
        jdbc.update("UPDATE trade_transactions SET updated_at = ? WHERE trade_id = ?", LocalDateTime.now().minusHours(2), tradeId);
    }

    /** Simulates a Hardhat restart plus redeployment at the same address, reviewed by the operator. */
    protected void resetChainAndAcceptNewDeployment() {
        chain.resetChainAndRedeploySameAddress();
        chain.acceptCurrentDeploymentInConfiguration();
    }

    protected long openAlerts(String type, String tradeId) {
        return alertRepository.findByTradeRefAndResolvedAtIsNull(tradeId).stream().filter(a -> a.getAlertType().equals(type)).count();
    }

    protected long audits(String action) {
        return auditLogRepository.findAll().stream().filter(a -> a.getAction().equals(action)).count();
    }
}
