package com.energytrading.service;

import com.energytrading.blockchain.BlockchainGateway;
import com.energytrading.blockchain.FakeBlockchainGateway;
import com.energytrading.config.BlockchainProperties;
import com.energytrading.model.enums.TradeStatus;
import com.energytrading.repository.SignerLeaseRepository;
import com.energytrading.repository.TradeSubmissionAttemptRepository;
import com.energytrading.service.TradeSettlementService.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase F: cross-instance signer lease, fencing, lease loss and crash recovery (two simulated instances). */
class SignerLeaseTests extends FakeChainTestSupport {

    @Autowired private SignerLeaseRepository leaseRepository;
    @Autowired private TradeSubmissionAttemptRepository attemptRepository;
    @Autowired private TradeStateService tradeStateService;
    @Autowired private AuditService auditService;
    @Autowired private BlockchainAlertService alertService;
    @Autowired private BlockchainProperties properties;
    @Autowired private ApplicationContext context;
    @Autowired private Clock clock;

    private static final String SIGNER = FakeBlockchainGateway.SIGNER;

    private SignerLeaseService instance(String id) {
        return new SignerLeaseService(leaseRepository, properties, transactionManager, id);
    }

    /** A second backend instance: its own settlement service and lease identity, same database and chain. */
    private TradeSettlementService otherInstance(SignerLeaseService lease) {
        return new TradeSettlementService(context.getBeanProvider(BlockchainGateway.class), tradeRepository, attemptRepository,
                tradeStateService, chainMapper, auditService, alertService, lease, properties, clock, transactionManager);
    }

    private void expireLease() {
        jdbc.update("UPDATE blockchain_signer_lease SET expires_at = ?", LocalDateTime.now().minusMinutes(5));
    }

    @Test
    @DisplayName("F: only one instance holds the lease; the holder renews with the same fencing token")
    void exclusiveLease() {
        SignerLeaseService a = instance("instance-A");
        SignerLeaseService b = instance("instance-B");
        Optional<SignerLeaseService.Lease> first = a.acquire(SIGNER);
        assertThat(first).isPresent();
        assertThat(b.acquire(SIGNER)).as("held by A").isEmpty();
        assertThat(a.acquire(SIGNER)).get().satisfies(l -> assertThat(l.token()).isEqualTo(first.get().token()));
    }

    @Test
    @DisplayName("F: an expired lease (crashed holder) is taken over with a new fencing token; the old holder is fenced out")
    void takeoverAfterExpiry() {
        SignerLeaseService a = instance("instance-A");
        SignerLeaseService b = instance("instance-B");
        SignerLeaseService.Lease leaseA = a.acquire(SIGNER).orElseThrow();
        expireLease();

        SignerLeaseService.Lease leaseB = b.acquire(SIGNER).orElseThrow();
        assertThat(leaseB.token()).isEqualTo(leaseA.token() + 1);
        assertThat(a.stillValid(leaseA, java.time.Duration.ZERO)).as("stale holder fenced").isFalse();
        assertThat(a.acquire(SIGNER)).as("B is the live holder").isEmpty();
        assertThat(b.stillValid(leaseB, java.time.Duration.ZERO)).isTrue();
    }

    @Test
    @DisplayName("F: a competing instance without the lease does not sign; the trade stays PENDING until the holder submits")
    void competingSubmitter() {
        String tradeId = pendingTrade("1");
        expireLease();
        SignerLeaseService b = instance("instance-B");
        assertThat(b.acquire(SIGNER)).isPresent();

        assertThat(settlement.submit(tradeId)).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(trade(tradeId).getStatus()).isEqualTo(TradeStatus.PENDING);
        assertThat(attemptRepository.countByTradeId(trade(tradeId).getId())).isZero();
        assertThat(alertRepository.findByOpenKey("LEASE:" + SIGNER)).isPresent();

        assertThat(otherInstance(b).submit(tradeId)).as("the lease holder submits").isEqualTo(Outcome.CONFIRMED);
        assertThat(chain.broadcasts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("F: lease lost between signing and write-ahead: nothing is recorded and nothing is broadcast")
    void leaseLostBeforeWriteAhead() {
        String tradeId = pendingTrade("1");
        SignerLeaseService b = instance("instance-B");
        chain.onSign = hash -> {
            expireLease();
            b.acquire(SIGNER).orElseThrow();
        };

        assertThat(settlement.submit(tradeId)).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(trade(tradeId).getStatus()).isEqualTo(TradeStatus.PENDING);
        assertThat(trade(tradeId).getTxHash()).isNull();
        assertThat(attemptRepository.countByTradeId(trade(tradeId).getId())).isZero();
        assertThat(chain.broadcasts.get()).isZero();
    }

    @Test
    @DisplayName("F: crash after write-ahead but before broadcast is recovered by the next lease holder with the same nonce")
    void crashRecovery() {
        String tradeId = pendingTrade("1");
        chain.onBroadcast = hash -> {
            throw new IllegalStateException("simulated process crash before the transaction left the process");
        };
        assertThat(settlement.submit(tradeId)).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(trade(tradeId).getStatus()).isEqualTo(TradeStatus.SUBMITTED);
        long nonce = trade(tradeId).getTxNonce();
        assertThat(chain.broadcasts.get()).isZero();

        chain.onBroadcast = hash -> { };
        expireLease(); // the crashed instance never renews
        TradeSettlementService survivor = otherInstance(instance("instance-B"));
        assertThat(survivor.reconcile(tradeId)).as("within the grace period: wait").isEqualTo(Outcome.SUBMITTED);
        ageSubmission(tradeId);
        assertThat(survivor.reconcile(tradeId)).isEqualTo(Outcome.RETRY);
        assertThat(survivor.reconcile(tradeId)).isEqualTo(Outcome.CONFIRMED);
        assertThat(trade(tradeId).getTxNonce()).as("same nonce: at most one attempt can ever be mined").isEqualTo(nonce);
        assertThat(chain.recordedTradeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("F: a nonce conflict reported by the node is retried with a fresh nonce, never double-recorded")
    void nonceConflict() {
        confirmedTrade("1"); // consumes nonce 0
        String tradeId = pendingTrade("1");
        jdbc.update("UPDATE trade_transactions SET tx_nonce = 0 WHERE trade_id = ?", tradeId); // stale pinned nonce

        assertThat(settlement.submit(tradeId)).isEqualTo(Outcome.RETRY);
        assertThat(trade(tradeId).getTxNonce()).isNull();
        assertThat(settlement.submit(tradeId)).isEqualTo(Outcome.CONFIRMED);
        assertThat(settlement.attempts(tradeId)).extracting(a -> a.status()).containsExactly("REJECTED", "MINED_SUCCESS");
        assertThat(chain.recordedTradeCount()).isEqualTo(2);
    }
}
