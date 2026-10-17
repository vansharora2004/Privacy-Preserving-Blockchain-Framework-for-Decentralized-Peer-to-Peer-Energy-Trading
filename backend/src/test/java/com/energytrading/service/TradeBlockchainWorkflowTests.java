package com.energytrading.service;

import com.energytrading.blockchain.BlockchainGateway;
import com.energytrading.blockchain.FakeBlockchainGateway;
import com.energytrading.blockchain.FakeBlockchainGateway.Mode;
import com.energytrading.blockchain.TradeChainMapper;
import com.energytrading.dto.TradeRequest;
import com.energytrading.dto.TradeResult;
import com.energytrading.model.EnergyOffer;
import com.energytrading.model.TradeTransaction;
import com.energytrading.model.enums.Role;
import com.energytrading.model.enums.TradeStatus;
import com.energytrading.repository.AuditLogRepository;
import com.energytrading.repository.EnergyOfferRepository;
import com.energytrading.repository.TradeEventRepository;
import com.energytrading.repository.TradeTransactionRepository;
import com.energytrading.repository.UserRepository;
import com.energytrading.service.TradeSettlementService.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 8 workflow with a deterministic fake chain (see Web3jBlockchainGatewayIT for the real node). */
@SpringBootTest(properties = {
        // Own in-memory database: this class has its own context, and create-drop must not touch the shared one
        "spring.datasource.url=jdbc:h2:mem:chaintest;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL;LOCK_TIMEOUT=10000",
        "app.blockchain.receipt-timeout=1s",
        "app.blockchain.receipt-poll-interval=100ms",
        "app.blockchain.unknown-transaction-grace=60s",
        "app.blockchain.max-submission-attempts=3"
})
@Import(TradeBlockchainWorkflowTests.FakeChainConfig.class)
class TradeBlockchainWorkflowTests {

    @TestConfiguration
    static class FakeChainConfig {
        @Bean
        FakeBlockchainGateway fakeBlockchainGateway() {
            return new FakeBlockchainGateway();
        }
    }

    @Autowired private FakeBlockchainGateway chain;
    @Autowired private TradingService tradingService;
    @Autowired private TradeSettlementService settlement;
    @Autowired private MarketplaceService marketplaceService;
    @Autowired private MeterReadingService meterReadingService;
    @Autowired private UserRepository userRepository;
    @Autowired private EnergyOfferRepository offerRepository;
    @Autowired private TradeTransactionRepository tradeRepository;
    @Autowired private TradeEventRepository eventRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private TradeChainMapper chainMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private MarketplaceFixtures fx;
    private String seller;
    private String buyer;
    private String offerId;

    @BeforeEach
    void setUp() {
        chain.reset();
        settlement.invalidateVerification();
        fx = new MarketplaceFixtures(userRepository, passwordEncoder, meterReadingService, marketplaceService);
        seller = fx.household("CSELL", "0.00");
        buyer = fx.household("CBUY", "100.00");
        fx.validReading(seller, "8.0", "3.0");
        offerId = fx.offer(seller, "5", "5.00");
    }

    private TradeResult buy(String quantity) {
        return tradingService.requestTrade(buyer, new TradeRequest(offerId, new BigDecimal(quantity)), "key-" + UUID.randomUUID());
    }

    /** Creates a trade while the RPC is down, so it stays PENDING for the test to drive. */
    private String pendingTrade(String quantity) {
        chain.rpcDown = true;
        TradeResult result = buy(quantity);
        chain.rpcDown = false;
        assertThat(result.outcome()).isEqualTo("PENDING");
        return result.trade().tradeId();
    }

    private TradeTransaction trade(String tradeId) {
        return tradeRepository.findByTradeId(tradeId).orElseThrow();
    }

    private BigDecimal balance(String code) {
        return userRepository.findByHouseholdCode(code).orElseThrow().getBalance();
    }

    private double remaining() {
        return offerRepository.findByOfferId(offerId).map(EnergyOffer::getRemainingKwh).orElseThrow();
    }

    private List<String> history(String tradeId) {
        return eventRepository.findByTradeIdOrderByIdAsc(trade(tradeId).getId()).stream().map(e -> e.getToStatus().name()).toList();
    }

    private BlockchainGateway.TradeRecordPayload payload(String tradeId) {
        return new TransactionTemplate(transactionManager).execute(tx -> {
            TradeTransaction t = trade(tradeId);
            return chainMapper.payload(t, t.getSeller().getHouseholdCode(), t.getBuyer().getHouseholdCode());
        });
    }

    @Test
    @DisplayName("Success: hash persisted before broadcast, receipt confirms, seller paid, details stored")
    void successfulSubmission() {
        AtomicReference<Map<String, Object>> rowAtBroadcast = new AtomicReference<>();
        chain.onBroadcast = hash -> rowAtBroadcast.set(jdbc.queryForMap(
                "SELECT status, tx_hash, tx_nonce, submission_attempts FROM trade_transactions WHERE tx_hash = ?", hash));

        TradeResult result = buy("2");

        assertThat(result.outcome()).isEqualTo("CONFIRMED");
        TradeTransaction t = trade(result.trade().tradeId());
        assertThat(rowAtBroadcast.get()).as("write-ahead: row was SUBMITTED with this hash before broadcast")
                .containsEntry("STATUS", "SUBMITTED").containsEntry("SUBMISSION_ATTEMPTS", 1);
        assertThat(t.getTxHash()).isEqualTo(chain.broadcastHashes().get(0));
        assertThat(t.getBlockNumber()).isGreaterThan(100L);
        assertThat(t.getChainId()).isEqualTo(FakeBlockchainGateway.CHAIN_ID);
        assertThat(t.getContractAddress()).isEqualTo(FakeBlockchainGateway.CONTRACT);
        assertThat(t.getConfirmedAt()).isNotNull();
        assertThat(t.getTxNonce()).isEqualTo(0L);
        assertThat(history(t.getTradeId())).containsExactly("PENDING", "SUBMITTED", "CONFIRMED");
        assertThat(balance(seller)).isEqualByComparingTo("10.00");
        assertThat(balance(buyer)).isEqualByComparingTo("90.00");
        assertThat(chain.broadcasts.get()).isEqualTo(1);
        assertThat(chain.recordedTradeCount()).isEqualTo(1);
        assertThat(result.trade().blockchain().txHash()).isEqualTo(t.getTxHash());

        var auth = new UsernamePasswordAuthenticationToken(buyer, null, List.of(new SimpleGrantedAuthority(Role.ROLE_HOUSEHOLD.name())));
        assertThat(tradingService.verify(auth, t.getTradeId()).result()).isEqualTo("MATCH");
        assertThat(auditLogRepository.findByActorUsername("SYSTEM"))
                .anyMatch(a -> a.getAction().equals("BLOCKCHAIN_SUBMIT") && a.getOutcome().equals("CONFIRMED"));
    }

    @Test
    @DisplayName("Reverted transaction: FAILED with reason, reservation and escrow released")
    void revertedTransaction() {
        chain.mode = Mode.REVERT;
        TradeResult result = buy("2");

        assertThat(result.outcome()).isEqualTo("FAILED");
        TradeTransaction t = trade(result.trade().tradeId());
        assertThat(t.getRejectionReason()).contains("reverted");
        assertThat(t.getBlockNumber()).isNotNull();
        assertThat(balance(buyer)).isEqualByComparingTo("100.00");
        assertThat(balance(seller)).isEqualByComparingTo("0.00");
        assertThat(remaining()).isEqualTo(5.0);
        assertThat(history(t.getTradeId())).containsExactly("PENDING", "SUBMITTED", "FAILED");
        assertThat(chain.recordedTradeCount()).isZero();
    }

    @Test
    @DisplayName("RPC failure before submission: trade stays PENDING (no broadcast) and is submitted once the RPC recovers")
    void rpcFailure() {
        String tradeId = pendingTrade("1");
        assertThat(chain.broadcasts.get()).isZero();
        assertThat(trade(tradeId).getTxHash()).isNull();

        assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.CONFIRMED);
        assertThat(trade(tradeId).getStatus()).isEqualTo(TradeStatus.CONFIRMED);
        assertThat(chain.broadcasts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Wrong chain ID or unauthorized signer: nothing is submitted and the failure is audited")
    void networkVerification() {
        String tradeId = pendingTrade("1");
        chain.reportedChainId = 1;
        assertThat(settlement.submit(tradeId)).isEqualTo(Outcome.UNAVAILABLE);
        chain.reportedChainId = FakeBlockchainGateway.CHAIN_ID;
        chain.signerAuthorized = false;
        assertThat(settlement.submit(tradeId)).isEqualTo(Outcome.UNAVAILABLE);

        assertThat(chain.broadcasts.get()).isZero();
        assertThat(trade(tradeId).getStatus()).isEqualTo(TradeStatus.PENDING);
        assertThat(auditLogRepository.findByActorUsername("SYSTEM"))
                .anyMatch(a -> a.getAction().equals("BLOCKCHAIN_VERIFY") && a.getDetails().contains("chain ID mismatch"))
                .anyMatch(a -> a.getAction().equals("BLOCKCHAIN_VERIFY") && a.getDetails().contains("not an authorized recorder"));
        assertThat(settlement.status().ok()).isFalse();
    }

    @Test
    @DisplayName("Uncertain outcome, actually mined, receipt lookups time out: stays SUBMITTED, reconciliation confirms without resubmitting")
    void uncertainButMined() {
        chain.mode = Mode.UNCERTAIN_MINED;
        chain.receiptFailures.set(1000);
        TradeResult result = buy("2");
        String tradeId = result.trade().tradeId();

        assertThat(result.outcome()).isEqualTo("SUBMITTED");
        assertThat(trade(tradeId).getTxHash()).isNotNull();
        assertThat(auditLogRepository.findByActorUsername("SYSTEM"))
                .anyMatch(a -> a.getAction().equals("BLOCKCHAIN_SUBMIT") && a.getOutcome().equals("UNCERTAIN"));

        chain.receiptFailures.set(0);
        assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.CONFIRMED);
        assertThat(chain.broadcasts.get()).as("no resubmission after the timeout").isEqualTo(1);
        assertThat(chain.recordedTradeCount()).isEqualTo(1);
        assertThat(balance(seller)).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("Uncertain outcome, never reached the node: kept SUBMITTED within the grace period, then resubmitted exactly once")
    void uncertainAndLost() {
        chain.mode = Mode.UNCERTAIN_LOST;
        String tradeId = buy("2").trade().tradeId();
        assertThat(trade(tradeId).getStatus()).isEqualTo(TradeStatus.SUBMITTED);

        assertThat(settlement.reconcile(tradeId)).as("too early to conclude").isEqualTo(Outcome.SUBMITTED);
        assertThat(chain.broadcasts.get()).isEqualTo(1);

        jdbc.update("UPDATE trade_transactions SET submitted_at = ? WHERE trade_id = ?", LocalDateTime.now().minusMinutes(2), tradeId);
        chain.mode = Mode.SUCCESS;
        assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.RETRY);
        assertThat(trade(tradeId).getStatus()).isEqualTo(TradeStatus.PENDING);
        assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.CONFIRMED);

        assertThat(chain.broadcasts.get()).isEqualTo(2);
        assertThat(chain.recordedTradeCount()).isEqualTo(1);
        assertThat(trade(tradeId).getSubmissionAttempts()).isEqualTo(2);
        assertThat(history(tradeId)).containsExactly("PENDING", "SUBMITTED", "PENDING", "SUBMITTED", "CONFIRMED");
    }

    @Test
    @DisplayName("Node rejection: retried up to the attempt limit, then FAILED with the reservation released")
    void nodeRejection() {
        chain.mode = Mode.NODE_REJECTS;
        TradeResult result = buy("2");
        String tradeId = result.trade().tradeId();
        assertThat(result.outcome()).isEqualTo("PENDING");
        assertThat(trade(tradeId).getTxHash()).as("hash of a transaction the node never accepted is cleared").isNull();

        assertThat(settlement.submit(tradeId)).isEqualTo(Outcome.RETRY);
        assertThat(settlement.submit(tradeId)).isEqualTo(Outcome.FAILED);
        TradeTransaction t = trade(tradeId);
        assertThat(t.getStatus()).isEqualTo(TradeStatus.FAILED);
        assertThat(t.getSubmissionAttempts()).isEqualTo(3);
        // Remediation L2: the participant-facing reason no longer exposes raw node errors...
        assertThat(t.getRejectionReason()).contains("after 3 attempts").doesNotContain("nonce too low");
        // ...which are preserved, per attempt, in the administrator-only submission history.
        // (identical re-signed transactions share one attempt row; the trade counts every try)
        assertThat(settlement.attempts(tradeId)).isNotEmpty()
                .allSatisfy(a -> assertThat(a.detail()).contains("nonce too low"))
                .allSatisfy(a -> assertThat(a.status()).isEqualTo("REJECTED"));
        assertThat(balance(buyer)).isEqualByComparingTo("100.00");
        assertThat(remaining()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("Trade already on-chain (earlier attempt succeeded unnoticed): adopted from the event log, never resubmitted")
    void adoptsExistingOnChainRecord() {
        String tradeId = pendingTrade("2");
        String earlierHash = chain.recordOutOfBand(payload(tradeId));

        assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.CONFIRMED);
        TradeTransaction t = trade(tradeId);
        assertThat(t.getTxHash()).isEqualTo(earlierHash);
        assertThat(t.getRejectionReason()).isNull();
        assertThat(chain.broadcasts.get()).isZero();
        assertThat(balance(seller)).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("Mismatching on-chain record: the trade is not confirmed; it moves to NEEDS_REVIEW (reservation held) and is audited once")
    void onChainMismatch() {
        String tradeId = pendingTrade("2");
        chain.recordTampered(payload(tradeId));

        // Remediation H2/M1: a mismatch no longer leaves the trade PENDING for endless retries.
        assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.NEEDS_REVIEW);
        assertThat(trade(tradeId).getStatus()).isEqualTo(TradeStatus.NEEDS_REVIEW);
        assertThat(trade(tradeId).getReviewReason()).contains("differs from the database record");
        assertThat(balance(buyer)).as("escrow still held").isEqualByComparingTo("90.00");
        assertThat(chain.broadcasts.get()).isZero();
        assertThat(auditLogRepository.findByActorUsername("SYSTEM"))
                .anyMatch(a -> a.getAction().equals("BLOCKCHAIN_RECONCILE") && a.getDetails().contains(tradeId));
    }

    @Test
    @DisplayName("Pending transaction: stays SUBMITTED (not confirmed) until mined, and is never expired as a reservation")
    void pendingTransaction() {
        chain.mode = Mode.PENDING_FOREVER;
        String tradeId = buy("2").trade().tradeId();
        assertThat(trade(tradeId).getStatus()).isEqualTo(TradeStatus.SUBMITTED);
        assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.SUBMITTED);

        jdbc.update("UPDATE trade_transactions SET updated_at = ? WHERE trade_id = ?", LocalDateTime.now().minusHours(2), tradeId);
        tradingService.expireStaleReservations();
        assertThat(trade(tradeId).getStatus()).as("outcome uncertain: never expired").isEqualTo(TradeStatus.SUBMITTED);
        assertThat(balance(seller)).isEqualByComparingTo("0.00");

        chain.mineAllPending();
        assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.CONFIRMED);
        assertThat(balance(seller)).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("Duplicate submissions: concurrent submit calls broadcast once; re-submitting a confirmed trade is a no-op")
    void duplicateSubmissionGuard() throws Exception {
        String tradeId = pendingTrade("2");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return settlement.submit(tradeId);
            }));
        }
        start.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(outcomes).containsOnlyOnce(Outcome.CONFIRMED);
        assertThat(outcomes).filteredOn(o -> o == Outcome.SKIPPED).hasSize(3);
        assertThat(chain.broadcasts.get()).isEqualTo(1);
        assertThat(settlement.submit(tradeId)).isEqualTo(Outcome.SKIPPED);
        assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.SKIPPED);
        assertThat(chain.broadcasts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Reservation expiry checks the chain first: a PENDING trade already recorded is confirmed, not expired")
    void expiryAdoptsRecordedTrade() {
        String tradeId = pendingTrade("2");
        chain.recordOutOfBand(payload(tradeId));
        jdbc.update("UPDATE trade_transactions SET updated_at = ? WHERE trade_id = ?", LocalDateTime.now().minusMinutes(30), tradeId);

        tradingService.expireStaleReservations();
        assertThat(trade(tradeId).getStatus()).isEqualTo(TradeStatus.CONFIRMED);
    }

    @Test
    @DisplayName("reconcileAll retries PENDING and resolves SUBMITTED trades")
    void reconcileAll() {
        String pending = pendingTrade("1");
        chain.mode = Mode.PENDING_FOREVER;
        String submitted = buy("1").trade().tradeId();
        chain.mineAllPending();
        chain.mode = Mode.SUCCESS;

        tradingService.reconcileAll();
        assertThat(trade(pending).getStatus()).isEqualTo(TradeStatus.CONFIRMED);
        assertThat(trade(submitted).getStatus()).isEqualTo(TradeStatus.CONFIRMED);
        assertThat(chain.broadcastHashes()).as("each trade broadcast exactly once").doesNotHaveDuplicates();
    }
}
