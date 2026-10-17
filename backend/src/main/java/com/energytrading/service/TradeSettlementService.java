package com.energytrading.service;

import com.energytrading.blockchain.BlockchainGateway;
import com.energytrading.blockchain.BlockchainGateway.BroadcastResult;
import com.energytrading.blockchain.BlockchainGateway.NetworkStatus;
import com.energytrading.blockchain.BlockchainGateway.OnChainTrade;
import com.energytrading.blockchain.BlockchainGateway.ReceiptInfo;
import com.energytrading.blockchain.BlockchainGateway.RecordedLog;
import com.energytrading.blockchain.BlockchainGateway.SignedTransaction;
import com.energytrading.blockchain.BlockchainGateway.TradeRecordPayload;
import com.energytrading.blockchain.TradeChainMapper;
import com.energytrading.config.BlockchainProperties;
import com.energytrading.dto.BlockchainStatusView;
import com.energytrading.dto.SubmissionAttemptView;
import com.energytrading.model.TradeSubmissionAttempt;
import com.energytrading.model.TradeSubmissionAttempt.Purpose;
import com.energytrading.model.TradeSubmissionAttempt.Status;
import com.energytrading.model.TradeTransaction;
import com.energytrading.model.enums.TradeStatus;
import com.energytrading.repository.TradeSubmissionAttemptRepository;
import com.energytrading.repository.TradeTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Moves reserved trades onto the blockchain and keeps database and chain state consistent.
 * <p>
 * The database and the blockchain are separate systems; no step is atomic across both. The
 * workflow below makes every step recoverable and never guesses an outcome:
 * <ol>
 *   <li><b>Preconditions</b>: this instance holds the cross-instance signer lease; the network
 *       verifies (chain ID, contract code and version, configured deploymentId, signer authorized);
 *       the trade has no earlier attempt on a different deployment (unless an administrator approved
 *       a re-anchor to the current one).</li>
 *   <li>If the contract already records the trade key, the record is <b>adopted</b> only with full
 *       evidence (valid status, matching data, TradeRecorded event and its receipt).</li>
 *   <li><b>Write-ahead</b>: the signed transaction (hash, nonce, deployment) is stored as a submission
 *       attempt and the trade becomes SUBMITTED, in one transaction that re-validates the lease.</li>
 *   <li>Broadcast. A node error is checked against the receipt and the node's pool; no answer is an
 *       <b>uncertain</b> outcome, never a failure.</li>
 *   <li><b>Confirmation requires evidence</b>: a receipt with status 1 whose target is the configured
 *       contract and which contains TradeRecorded for this trade key, plus an on-chain record in
 *       Recorded/Settled status matching the stored data, on the unchanged deployment. A revert
 *       fails the trade only if the contract does not record it.</li>
 *   <li><b>Reconciliation</b> of SUBMITTED trades: a changed deployment or chain instance moves the
 *       trade to NEEDS_REVIEW (a reset chain proves nothing about the old one). On the same
 *       deployment, a transaction unknown to the node after the grace period is retried with the
 *       <b>same nonce</b> while that nonce is unused (so at most one attempt can ever be mined), or with
 *       a fresh nonce once the nonce is consumed by another transaction.</li>
 *   <li>Anything contradictory or incomplete becomes <b>NEEDS_REVIEW</b>: reservation and escrow stay
 *       held and no routine job touches the trade until an administrator resolves it.</li>
 * </ol>
 * Submissions are serialized within the JVM by a lock held only around nonce allocation, signing
 * and broadcast (receipt waits happen outside it) and across instances by the signer lease.
 */
@Service
public class TradeSettlementService {

    private static final Logger log = LoggerFactory.getLogger(TradeSettlementService.class);
    static final String ACTOR = "BLOCKCHAIN";
    static final String USER_FAILED_REVERT = "The blockchain transaction was reverted";
    static final String USER_REVIEW = "Held for administrator review";

    public enum Outcome { NOT_CONFIGURED, SKIPPED, UNAVAILABLE, BUSY, CONFIRMED, FAILED, SUBMITTED, RETRY, NEEDS_REVIEW }

    /** Result of comparing an on-chain record with the database record. */
    public enum Match { MATCH, MISMATCH, UNVERIFIABLE }

    /** Evidence about a trade on the current deployment, for automated and administrator decisions. */
    public record Evidence(String currentDeploymentId, boolean recordedOnCurrent, boolean validAnchor, Match match,
                           String eventTxHash, Long eventBlock, boolean eventReceiptConfirms,
                           boolean attemptsOnOtherDeployments, boolean attemptPendingOnCurrent, List<String> problems) {

        public boolean confirmsRecord() {
            return recordedOnCurrent && validAnchor && match != Match.MISMATCH && eventTxHash != null && eventReceiptConfirms;
        }
    }

    private final ObjectProvider<BlockchainGateway> gatewayProvider;
    private final TradeTransactionRepository tradeRepository;
    private final TradeSubmissionAttemptRepository attemptRepository;
    private final TradeStateService tradeStateService;
    private final TradeChainMapper chainMapper;
    private final AuditService auditService;
    private final BlockchainAlertService alertService;
    private final SignerLeaseService leaseService;
    private final BlockchainProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final ReentrantLock submissionLock = new ReentrantLock();

    private volatile NetworkStatus verified;
    private volatile Instant verifiedAt;

    public TradeSettlementService(ObjectProvider<BlockchainGateway> gatewayProvider, TradeTransactionRepository tradeRepository,
                                  TradeSubmissionAttemptRepository attemptRepository, TradeStateService tradeStateService,
                                  TradeChainMapper chainMapper, AuditService auditService, BlockchainAlertService alertService,
                                  SignerLeaseService leaseService, BlockchainProperties properties, Clock clock,
                                  PlatformTransactionManager transactionManager) {
        this.gatewayProvider = gatewayProvider;
        this.tradeRepository = tradeRepository;
        this.attemptRepository = attemptRepository;
        this.tradeStateService = tradeStateService;
        this.chainMapper = chainMapper;
        this.auditService = auditService;
        this.alertService = alertService;
        this.leaseService = leaseService;
        this.properties = properties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public boolean isEnabled() {
        return gatewayProvider.getIfAvailable() != null;
    }

    // ================================================================== status

    public BlockchainStatusView status() {
        BlockchainGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            return new BlockchainStatusView(false, properties.chainId(), null, properties.contractAddress(), null, null,
                    null, null, null, properties.deploymentId(), null, null, false,
                    "Blockchain integration is disabled (BLOCKCHAIN_ENABLED=false)");
        }
        try {
            NetworkStatus s = gateway.verifyNetwork();
            return new BlockchainStatusView(true, s.expectedChainId(), s.actualChainId(), s.contractAddress(),
                    s.contractDeployed(), s.contractVersion(), s.signerAddress(), s.signerAuthorized(), s.latestBlock(),
                    s.expectedDeploymentId(), s.deploymentId(), s.genesisHash(), s.ok(), s.problem());
        } catch (IOException e) {
            return new BlockchainStatusView(true, gateway.chainId(), null, gateway.contractAddress(), null, null,
                    gateway.signerAddress(), null, null, properties.deploymentId(), null, null, false, "RPC unavailable");
        }
    }

    /** Verifies the network once (cached on success); failures raise a de-duplicated alert. */
    public boolean networkReady() {
        BlockchainGateway gateway = gatewayProvider.getIfAvailable();
        return gateway != null && verifiedNetwork(gateway, false) != null;
    }

    public void invalidateVerification() {
        verified = null;
    }

    // ================================================================== submission

    /** Submits a PENDING trade. Safe to call repeatedly and concurrently; never throws. */
    public Outcome submit(String tradeId) {
        return run(tradeId, Purpose.SUBMIT);
    }

    /** Re-anchors a CONFIRMED trade on the current deployment (administrator-approved; no money moves). */
    public Outcome submitReanchor(String tradeId) {
        return run(tradeId, Purpose.REANCHOR);
    }

    private Outcome run(String tradeId, Purpose purpose) {
        BlockchainGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            return Outcome.NOT_CONFIGURED;
        }
        InFlight inFlight;
        try {
            if (!submissionLock.tryLock(properties.submitLockWait().toMillis(), TimeUnit.MILLISECONDS)) {
                return Outcome.BUSY; // a slow node must not block purchases; the scheduler retries
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.BUSY;
        }
        try {
            Object result = criticalSection(gateway, tradeId, purpose);
            if (result instanceof Outcome outcome) {
                return outcome;
            }
            inFlight = (InFlight) result;
        } catch (IOException e) {
            log.warn("Blockchain RPC error while submitting trade {}: {}", tradeId, e.getMessage());
            return Outcome.UNAVAILABLE;
        } catch (RuntimeException e) {
            log.error("Unexpected error while submitting trade {}", tradeId, e);
            return Outcome.UNAVAILABLE;
        } finally {
            submissionLock.unlock();
        }
        // Outside the lock: waiting for a receipt must not block other submissions.
        try {
            Optional<ReceiptInfo> receipt = awaitReceipt(gateway, inFlight.hash());
            if (receipt.isPresent()) {
                return finalizeReceipt(gateway, inFlight.network(), tradeId, inFlight.payload(), receipt.get(), purpose);
            }
            log.info("No receipt yet for trade {} ({}); left for reconciliation", tradeId, inFlight.hash());
            return Outcome.SUBMITTED;
        } catch (IOException e) {
            log.warn("Blockchain RPC error while awaiting trade {}: {}", tradeId, e.getMessage());
            return Outcome.SUBMITTED;
        }
    }

    private record InFlight(String hash, TradeRecordPayload payload, NetworkStatus network) {
    }

    /** Returns an Outcome when finished, or an InFlight when a receipt must be awaited. */
    private Object criticalSection(BlockchainGateway gateway, String tradeId, Purpose purpose) throws IOException {
        Optional<SignerLeaseService.Lease> lease = leaseService.acquire(gateway.signerAddress());
        if (lease.isEmpty()) {
            alertService.raise(BlockchainAlertService.LEASE, "LEASE:" + gateway.signerAddress().toLowerCase(), null,
                    "signer lease is held by another backend instance; this instance will not sign", "BLOCKCHAIN_LEASE");
            return Outcome.UNAVAILABLE;
        }
        NetworkStatus net = verifiedNetwork(gateway, true);
        if (net == null) {
            return Outcome.UNAVAILABLE;
        }
        Snapshot snap = snapshot(tradeId);
        if (snap == null) {
            return Outcome.SKIPPED;
        }
        if (purpose == Purpose.SUBMIT) {
            if (snap.status() != TradeStatus.PENDING) {
                return Outcome.SKIPPED;
            }
            if (attemptRepository.existsByTradeIdAndDeploymentIdNot(snap.id(), net.deploymentId())
                    && !net.deploymentId().equalsIgnoreCase(snap.reanchorDeploymentId())) {
                return toReview(tradeId, "earlier submission attempt(s) were made on a different deployment; their outcome "
                        + "cannot be established from deployment " + net.deploymentId());
            }
        } else {
            if (snap.status() != TradeStatus.CONFIRMED || net.deploymentId().equalsIgnoreCase(snap.deploymentId())
                    || !net.deploymentId().equalsIgnoreCase(snap.reanchorDeploymentId())) {
                return Outcome.SKIPPED;
            }
        }
        TradeRecordPayload payload = snap.payload();
        if (gateway.isTradeRecorded(payload.tradeKey())) {
            return purpose == Purpose.SUBMIT
                    ? adoptRecorded(gateway, net, tradeId, payload, "already recorded on-chain before submission")
                    : anchorFromChain(gateway, net, tradeId, payload);
        }
        if (purpose == Purpose.REANCHOR && reanchorInFlight(gateway, snap.id(), net)) {
            return Outcome.SUBMITTED; // an earlier re-anchor transaction is still held by the node
        }
        Long pinned = purpose == Purpose.SUBMIT ? snap.txNonce() : null;
        java.math.BigInteger nonce = pinned != null ? java.math.BigInteger.valueOf(pinned) : gateway.pendingNonce();
        SignedTransaction signed = gateway.signRecordTrade(payload, nonce);

        Boolean marked;
        try {
            marked = transactionTemplate.execute(tx -> writeAhead(lease.get(), net, tradeId, purpose, signed));
        } catch (LeaseLostException e) {
            return Outcome.UNAVAILABLE;
        }
        if (!Boolean.TRUE.equals(marked)) {
            return Outcome.SKIPPED;
        }
        // Do not broadcast on a lease that may expire while the transaction is in flight.
        if (!leaseService.stillValid(lease.get(), properties.signerLeaseTtl().dividedBy(3))) {
            abandon(tradeId, signed.hash(), purpose);
            return Outcome.UNAVAILABLE;
        }

        BroadcastResult broadcast = gateway.broadcast(signed);
        switch (broadcast.kind()) {
            case REJECTED -> {
                Optional<ReceiptInfo> receipt = gateway.receipt(signed.hash());
                if (receipt.isPresent()) {
                    return new InFlight(signed.hash(), payload, net); // mined (e.g. a revert in automine mode)
                }
                if (gateway.isTransactionKnown(signed.hash())) {
                    updateAttempt(signed.hash(), Status.BROADCAST, null, "node answered with an error but holds the transaction");
                    return new InFlight(signed.hash(), payload, net);
                }
                updateAttempt(signed.hash(), Status.REJECTED, null, "node rejected the transaction: " + broadcast.message());
                return purpose == Purpose.SUBMIT ? notAccepted(tradeId, signed.hash(), false) : Outcome.FAILED;
            }
            case UNKNOWN -> {
                updateAttempt(signed.hash(), Status.UNCERTAIN, null, "no response from the node: " + broadcast.message());
                auditService.record("SYSTEM", "BLOCKCHAIN_SUBMIT", "UNCERTAIN", "tradeId=" + tradeId + " txHash=" + signed.hash());
            }
            default -> updateAttempt(signed.hash(), Status.BROADCAST, null, "accepted by the node");
        }
        return new InFlight(signed.hash(), payload, net);
    }

    private static class LeaseLostException extends RuntimeException {
    }

    private Boolean writeAhead(SignerLeaseService.Lease lease, NetworkStatus net, String tradeId, Purpose purpose,
                               SignedTransaction signed) {
        if (!leaseService.validate(lease, Duration.ZERO)) {
            throw new LeaseLostException(); // rolls back: nothing is recorded, nothing is broadcast
        }
        TradeTransaction t = tradeRepository.lockByTradeId(tradeId).orElseThrow();
        if (purpose == Purpose.SUBMIT && t.getStatus() != TradeStatus.PENDING) {
            return false;
        }
        if (purpose == Purpose.REANCHOR && t.getStatus() != TradeStatus.CONFIRMED) {
            return false;
        }
        int attemptNo = (int) attemptRepository.countByTradeId(t.getId()) + 1;
        Optional<TradeSubmissionAttempt> sameTx = attemptRepository.findByTxHashIgnoreCase(signed.hash());
        if (sameTx.isPresent()) {
            // Identical signed transaction (same nonce and data): rebroadcasting it is idempotent.
            sameTx.get().setStatus(Status.SIGNED);
            sameTx.get().setDetail("identical transaction re-signed for rebroadcast");
            attemptRepository.save(sameTx.get());
        } else {
            attemptRepository.save(new TradeSubmissionAttempt(t, attemptNo, purpose, signed.hash(), signed.nonce().longValue(),
                    net.actualChainId(), net.contractAddress(), net.deploymentId(), net.genesisHash(), net.signerAddress(),
                    lease.token(), Status.SIGNED, "signed and persisted before broadcast"));
        }
        if (purpose == Purpose.SUBMIT) {
            t.setTxHash(signed.hash());
            t.setTxNonce(signed.nonce().longValue());
            t.setSubmittedAt(LocalDateTime.now(clock));
            t.setSubmissionAttempts(t.getSubmissionAttempts() + 1);
            t.setChainId(net.actualChainId());
            t.setContractAddress(net.contractAddress());
            t.setDeploymentId(net.deploymentId());
            t.setChainGenesisHash(net.genesisHash());
            t.setRejectionReason(null);
            tradeStateService.transition(t, TradeStatus.SUBMITTED, "SYSTEM",
                    "submitted to the blockchain (attempt " + t.getSubmissionAttempts() + ")");
        } else {
            tradeStateService.transition(t, TradeStatus.CONFIRMED, "SYSTEM", "re-anchor submitted to the current deployment");
        }
        return true;
    }

    private void abandon(String tradeId, String hash, Purpose purpose) {
        updateAttempt(hash, Status.ABANDONED, null, "abandoned before broadcast: signer lease lost or about to expire");
        if (purpose != Purpose.SUBMIT) {
            return;
        }
        transactionTemplate.executeWithoutResult(tx -> {
            TradeTransaction t = tradeRepository.lockByTradeId(tradeId).orElseThrow();
            if (t.getStatus() == TradeStatus.SUBMITTED && hash.equalsIgnoreCase(t.getTxHash())) {
                t.setTxHash(null);
                t.setTxNonce(null);
                tradeStateService.transition(t, TradeStatus.PENDING, "SYSTEM", "submission postponed before broadcast; will retry");
            }
        });
    }

    // ================================================================== reconciliation

    /**
     * Resolves a SUBMITTED trade from evidence, (re)submits a PENDING one, and never touches
     * NEEDS_REVIEW or terminal trades.
     */
    public Outcome reconcile(String tradeId) {
        BlockchainGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            return Outcome.NOT_CONFIGURED;
        }
        Snapshot snap = snapshot(tradeId);
        if (snap == null) {
            return Outcome.SKIPPED;
        }
        if (snap.status() == TradeStatus.PENDING) {
            return submit(tradeId);
        }
        if (snap.status() != TradeStatus.SUBMITTED) {
            return Outcome.SKIPPED;
        }
        try {
            if (!submissionLock.tryLock(properties.submitLockWait().toMillis(), TimeUnit.MILLISECONDS)) {
                return Outcome.BUSY;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.BUSY;
        }
        NetworkStatus net;
        Optional<ReceiptInfo> receipt;
        try {
            if (leaseService.acquire(gateway.signerAddress()).isEmpty()) {
                return Outcome.UNAVAILABLE; // only the lease holder reasons about this signer's transactions
            }
            net = verifiedNetwork(gateway, true);
            if (net == null) {
                return Outcome.UNAVAILABLE;
            }
            if (deploymentChanged(snap, net)) {
                return toReview(tradeId, "deployment changed since submission (submitted on " + snap.deploymentId()
                        + ", current " + net.deploymentId() + "); a missing transaction on the new deployment proves nothing");
            }
            receipt = gateway.receipt(snap.txHash());
            if (receipt.isEmpty()) {
                if (gateway.isTradeRecorded(snap.payload().tradeKey())) {
                    return adoptRecorded(gateway, net, tradeId, snap.payload(), "found on-chain during reconciliation");
                }
                if (gateway.isTransactionKnown(snap.txHash())) {
                    return Outcome.SUBMITTED; // still pending in the node's pool
                }
                Duration age = Duration.between(snap.submittedAt(), LocalDateTime.now(clock));
                if (age.compareTo(properties.unknownTransactionGrace()) < 0) {
                    return Outcome.SUBMITTED; // too early to conclude
                }
                long latest = gateway.latestNonce().longValue();
                boolean nonceConsumed = snap.txNonce() != null && latest > snap.txNonce();
                updateAttempt(snap.txHash(), Status.DROPPED, null, "unknown to the node after " + age.toSeconds() + "s; nonce "
                        + (nonceConsumed ? "consumed by another transaction" : "still unused"));
                return nonceConsumed ? notAccepted(tradeId, snap.txHash(), false) : notAccepted(tradeId, snap.txHash(), true);
            }
        } catch (IOException e) {
            log.warn("Blockchain RPC error while reconciling trade {}: {}", tradeId, e.getMessage());
            return Outcome.UNAVAILABLE;
        } finally {
            submissionLock.unlock();
        }
        try {
            return finalizeReceipt(gateway, net, tradeId, snap.payload(), receipt.get(), Purpose.SUBMIT);
        } catch (IOException e) {
            return Outcome.UNAVAILABLE;
        }
    }

    /** Decision for a stale PENDING reservation (never applied to SUBMITTED or NEEDS_REVIEW). */
    public enum ExpiryDecision { EXPIRE, HOLD, RESOLVED }

    public ExpiryDecision expiryDecision(String tradeId) {
        Snapshot snap = snapshot(tradeId);
        if (snap == null || snap.status() != TradeStatus.PENDING) {
            return ExpiryDecision.HOLD;
        }
        boolean hasAttempts = attemptRepository.countByTradeId(snap.id()) > 0;
        BlockchainGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            if (!hasAttempts) {
                return ExpiryDecision.EXPIRE; // never broadcast anywhere
            }
            toReview(tradeId, "reservation timed out with earlier blockchain attempts while the blockchain is disabled");
            return ExpiryDecision.RESOLVED;
        }
        NetworkStatus net = verifiedNetwork(gateway, true);
        if (net == null) {
            return hasAttempts ? ExpiryDecision.HOLD : ExpiryDecision.EXPIRE;
        }
        try {
            if (gateway.isTradeRecorded(snap.payload().tradeKey())) {
                adoptRecorded(gateway, net, tradeId, snap.payload(), "found on-chain while expiring the reservation");
                return ExpiryDecision.RESOLVED;
            }
            if (attemptRepository.existsByTradeIdAndDeploymentIdNot(snap.id(), net.deploymentId())) {
                toReview(tradeId, "reservation timed out with attempts on a different deployment");
                return ExpiryDecision.RESOLVED;
            }
            for (TradeSubmissionAttempt a : attemptRepository.findByTradeIdOrderByAttemptNoAsc(snap.id())) {
                if (gateway.isTransactionKnown(a.getTxHash())) {
                    return ExpiryDecision.HOLD; // an earlier transaction is still known to the node
                }
            }
            return ExpiryDecision.EXPIRE;
        } catch (IOException e) {
            return ExpiryDecision.HOLD;
        }
    }

    // ================================================================== confirmation and adoption

    private Outcome finalizeReceipt(BlockchainGateway gateway, NetworkStatus net, String tradeId, TradeRecordPayload payload,
                                    ReceiptInfo receipt, Purpose purpose) throws IOException {
        if (!receipt.success()) {
            updateAttempt(receipt.transactionHash(), Status.MINED_REVERTED, receipt.blockNumber(),
                    "reverted" + (receipt.revertReason() != null ? ": " + receipt.revertReason() : ""));
            if (gateway.isTradeRecorded(payload.tradeKey())) {
                return purpose == Purpose.SUBMIT
                        ? adoptRecorded(gateway, net, tradeId, payload, "receipt reverted but trade already recorded")
                        : anchorFromChain(gateway, net, tradeId, payload);
            }
            if (purpose == Purpose.REANCHOR) {
                return Outcome.FAILED;
            }
            Boolean done = transactionTemplate.execute(tx -> {
                TradeTransaction t = tradeRepository.lockByTradeId(tradeId).orElseThrow();
                if (t.getStatus() != TradeStatus.SUBMITTED || !receipt.transactionHash().equalsIgnoreCase(t.getTxHash())) {
                    return false;
                }
                t.setBlockNumber(receipt.blockNumber());
                tradeStateService.release(t, TradeStatus.FAILED, ACTOR, USER_FAILED_REVERT);
                return true;
            });
            if (Boolean.TRUE.equals(done)) {
                auditService.record("SYSTEM", "BLOCKCHAIN_SUBMIT", "FAILED", "tradeId=" + tradeId + " reverted in block " + receipt.blockNumber());
                return Outcome.FAILED;
            }
            return Outcome.SKIPPED;
        }
        List<String> problems = new ArrayList<>();
        if (!receipt.recordedByContract(net.contractAddress(), payload.tradeKey())) {
            problems.add("receipt does not target the configured contract or lacks TradeRecorded for this trade key");
        }
        Optional<OnChainTrade> onChain = gateway.getTrade(payload.tradeKey());
        if (onChain.isEmpty()) {
            problems.add("contract holds no record for this trade key");
        } else {
            if (!onChain.get().validAnchor()) {
                problems.add("on-chain record is VOIDED");
            }
            Match match = compare(onChain.get(), payload, snapshotRefsStored(tradeId));
            if (match != Match.MATCH) {
                problems.add("on-chain record " + (match == Match.UNVERIFIABLE ? "has unverifiable party references" : "differs from the database record"));
            }
        }
        if (!problems.isEmpty()) {
            updateAttempt(receipt.transactionHash(), Status.MINED_SUCCESS, receipt.blockNumber(), "evidence inconsistent: " + problems.get(0));
            if (purpose == Purpose.REANCHOR) {
                alertService.raise(BlockchainAlertService.ANCHOR_INVALID, "ANCHOR:" + tradeId, tradeId,
                        "re-anchor receipt evidence inconsistent: " + String.join("; ", problems), "BLOCKCHAIN_ANCHOR");
                return Outcome.NEEDS_REVIEW;
            }
            return toReview(tradeId, "receipt evidence inconsistent: " + String.join("; ", problems));
        }
        updateAttempt(receipt.transactionHash(), Status.MINED_SUCCESS, receipt.blockNumber(), "mined with status 1; evidence verified");
        if (purpose == Purpose.REANCHOR) {
            return applyAnchor(tradeId, net, receipt.transactionHash(), receipt.blockNumber(), "re-anchored on deployment " + net.deploymentId());
        }
        Boolean done = transactionTemplate.execute(tx -> {
            TradeTransaction t = tradeRepository.lockByTradeId(tradeId).orElseThrow();
            if (t.getStatus() != TradeStatus.SUBMITTED || !receipt.transactionHash().equalsIgnoreCase(t.getTxHash())) {
                return false;
            }
            t.setDeploymentId(net.deploymentId());
            t.setChainGenesisHash(net.genesisHash());
            t.setAnchorCheckedAt(LocalDateTime.now(clock));
            tradeStateService.confirm(t, receipt.transactionHash(), receipt.blockNumber(), "confirmed by receipt and contract state");
            return true;
        });
        if (Boolean.TRUE.equals(done)) {
            auditService.record("SYSTEM", "BLOCKCHAIN_SUBMIT", "CONFIRMED", "tradeId=" + tradeId + " block=" + receipt.blockNumber());
            log.info("Trade {} confirmed in block {}", tradeId, receipt.blockNumber());
            return Outcome.CONFIRMED;
        }
        return Outcome.SKIPPED;
    }

    /** The contract records this key: confirm only with full evidence, otherwise NEEDS_REVIEW. */
    private Outcome adoptRecorded(BlockchainGateway gateway, NetworkStatus net, String tradeId, TradeRecordPayload payload,
                                  String detail) throws IOException {
        Evidence ev = evidence(gateway, net, tradeId, payload);
        if (!ev.confirmsRecord() || ev.match() != Match.MATCH) {
            List<String> problems = ev.problems().isEmpty() ? List.of("party references cannot be verified") : ev.problems();
            return toReview(tradeId, "on-chain record found but not adopted: " + String.join("; ", problems));
        }
        recordAdoptedAttempt(tradeId, net, ev);
        Boolean done = transactionTemplate.execute(tx -> {
            TradeTransaction t = tradeRepository.lockByTradeId(tradeId).orElseThrow();
            if (t.getStatus() != TradeStatus.SUBMITTED && t.getStatus() != TradeStatus.PENDING) {
                return false;
            }
            t.setChainId(net.actualChainId());
            t.setContractAddress(net.contractAddress());
            t.setDeploymentId(net.deploymentId());
            t.setChainGenesisHash(net.genesisHash());
            t.setAnchorCheckedAt(LocalDateTime.now(clock));
            tradeStateService.confirm(t, ev.eventTxHash(), ev.eventBlock(), "confirmed from on-chain evidence: " + detail);
            return true;
        });
        if (Boolean.TRUE.equals(done)) {
            auditService.record("SYSTEM", "BLOCKCHAIN_RECONCILE", "CONFIRMED", "tradeId=" + tradeId + " " + detail);
            return Outcome.CONFIRMED;
        }
        return Outcome.SKIPPED;
    }

    /** Re-anchor found the record already on the current deployment: verify and record the anchor. */
    private Outcome anchorFromChain(BlockchainGateway gateway, NetworkStatus net, String tradeId, TradeRecordPayload payload)
            throws IOException {
        Evidence ev = evidence(gateway, net, tradeId, payload);
        if (!ev.confirmsRecord()) {
            alertService.raise(BlockchainAlertService.ANCHOR_INVALID, "ANCHOR:" + tradeId, tradeId,
                    "record on the current deployment is not valid evidence: " + String.join("; ", ev.problems()), "BLOCKCHAIN_ANCHOR");
            return Outcome.NEEDS_REVIEW;
        }
        recordAdoptedAttempt(tradeId, net, ev);
        return applyAnchor(tradeId, net, ev.eventTxHash(), ev.eventBlock(), "anchor verified on deployment " + net.deploymentId());
    }

    private Outcome applyAnchor(String tradeId, NetworkStatus net, String txHash, long block, String detail) {
        Boolean done = transactionTemplate.execute(tx -> {
            TradeTransaction t = tradeRepository.lockByTradeId(tradeId).orElseThrow();
            if (t.getStatus() != TradeStatus.CONFIRMED) {
                return false;
            }
            t.setTxHash(txHash);
            t.setBlockNumber(block);
            t.setChainId(net.actualChainId());
            t.setContractAddress(net.contractAddress());
            t.setDeploymentId(net.deploymentId());
            t.setChainGenesisHash(net.genesisHash());
            t.setReanchorDeploymentId(null);
            t.setAnchorCheckedAt(LocalDateTime.now(clock));
            tradeStateService.transition(t, TradeStatus.CONFIRMED, ACTOR, detail);
            return true;
        });
        if (Boolean.TRUE.equals(done)) {
            alertService.resolveForTrade(tradeId, "SYSTEM", detail);
            auditService.record("SYSTEM", "BLOCKCHAIN_ANCHOR", "SUCCESS", "tradeId=" + tradeId + " " + detail);
            return Outcome.CONFIRMED;
        }
        return Outcome.SKIPPED;
    }

    /** Collects on-chain evidence for a trade on the verified current deployment. */
    private Evidence evidence(BlockchainGateway gateway, NetworkStatus net, String tradeId, TradeRecordPayload payload)
            throws IOException {
        List<String> problems = new ArrayList<>();
        Snapshot snap = snapshot(tradeId);
        boolean recorded = gateway.isTradeRecorded(payload.tradeKey());
        Optional<OnChainTrade> onChain = recorded ? gateway.getTrade(payload.tradeKey()) : Optional.empty();
        boolean valid = onChain.map(OnChainTrade::validAnchor).orElse(false);
        Match match = onChain.map(o -> compare(o, payload, snap.refsStored())).orElse(Match.MISMATCH);
        String eventHash = null;
        Long eventBlock = null;
        boolean receiptConfirms = false;
        if (!recorded) {
            problems.add("the trade is not recorded on the current deployment");
        } else {
            if (onChain.isEmpty()) {
                problems.add("contract returned no record");
            } else if (!valid) {
                problems.add("on-chain record is VOIDED");
            }
            if (match == Match.MISMATCH) {
                problems.add("on-chain record differs from the database record");
            } else if (match == Match.UNVERIFIABLE) {
                problems.add("party references cannot be verified (trade predates stored references); record digest matches");
            }
            Optional<RecordedLog> eventLog = gateway.findTradeRecordedLog(payload.tradeKey());
            if (eventLog.isEmpty()) {
                problems.add("no TradeRecorded event found for this trade key");
            } else {
                eventHash = eventLog.get().transactionHash();
                eventBlock = eventLog.get().blockNumber();
                Optional<ReceiptInfo> r = gateway.receipt(eventHash);
                receiptConfirms = r.isPresent() && r.get().success() && r.get().recordedByContract(net.contractAddress(), payload.tradeKey());
                if (!receiptConfirms) {
                    problems.add("the event's transaction receipt does not confirm the record");
                }
            }
        }
        boolean otherDeployments = attemptRepository.existsByTradeIdAndDeploymentIdNot(snap.id(), net.deploymentId());
        boolean pendingOnCurrent = false;
        for (TradeSubmissionAttempt a : attemptRepository.findByTradeIdOrderByAttemptNoAsc(snap.id())) {
            if (a.getDeploymentId().equalsIgnoreCase(net.deploymentId()) && gateway.receipt(a.getTxHash()).isEmpty()
                    && gateway.isTransactionKnown(a.getTxHash())) {
                pendingOnCurrent = true;
            }
        }
        return new Evidence(net.deploymentId(), recorded, valid, match, eventHash, eventBlock, receiptConfirms,
                otherDeployments, pendingOnCurrent, problems);
    }

    /** Evidence for administrator decisions; empty when the network cannot be verified (fail closed). */
    public Optional<Evidence> evidenceFor(String tradeId) {
        BlockchainGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            return Optional.empty();
        }
        NetworkStatus net = verifiedNetwork(gateway, true);
        Snapshot snap = snapshot(tradeId);
        if (net == null || snap == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(evidence(gateway, net, tradeId, snap.payload()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public Optional<NetworkStatus> currentNetwork() {
        BlockchainGateway gateway = gatewayProvider.getIfAvailable();
        return gateway == null ? Optional.empty() : Optional.ofNullable(verifiedNetwork(gateway, true));
    }

    /** Administrator-approved confirmation from evidence on the current deployment. */
    public Outcome confirmFromEvidence(String tradeId, String adminCode, String reason) {
        BlockchainGateway gateway = gatewayProvider.getIfAvailable();
        Optional<NetworkStatus> net = currentNetwork();
        Optional<Evidence> ev = evidenceFor(tradeId);
        if (gateway == null || net.isEmpty() || ev.isEmpty() || !ev.get().confirmsRecord()) {
            return Outcome.UNAVAILABLE;
        }
        recordAdoptedAttempt(tradeId, net.get(), ev.get());
        Boolean done = transactionTemplate.execute(tx -> {
            TradeTransaction t = tradeRepository.lockByTradeId(tradeId).orElseThrow();
            if (t.getStatus() != TradeStatus.NEEDS_REVIEW) {
                return false;
            }
            t.setChainId(net.get().actualChainId());
            t.setContractAddress(net.get().contractAddress());
            t.setDeploymentId(net.get().deploymentId());
            t.setChainGenesisHash(net.get().genesisHash());
            t.setAnchorCheckedAt(LocalDateTime.now(clock));
            t.setReviewReason(truncate("resolved as confirmed by " + adminCode + ": " + reason));
            tradeStateService.confirm(t, ev.get().eventTxHash(), ev.get().eventBlock(), "resolved as confirmed by administrator: " + reason);
            return true;
        });
        return Boolean.TRUE.equals(done) ? Outcome.CONFIRMED : Outcome.SKIPPED;
    }

    // ================================================================== anchor audit (A3)

    /**
     * Verifies CONFIRMED trades against the current deployment: deployment identity, on-chain record
     * (status Recorded/Settled, matching data) and the transaction receipt with its TradeRecorded event.
     * Problems raise ANCHOR_MISSING / ANCHOR_INVALID alerts; confirmed trades are never reversed.
     *
     * @return number of trades checked
     */
    public int anchorAudit() {
        BlockchainGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            return 0;
        }
        NetworkStatus net = verifiedNetwork(gateway, true);
        if (net == null) {
            return 0;
        }
        int checked = 0;
        // Drive approved re-anchors that were fenced, busy or left without a receipt (no money moves).
        for (TradeTransaction pending : tradeRepository.findByStatusAndReanchorDeploymentIdIgnoreCase(
                TradeStatus.CONFIRMED, net.deploymentId(), PageRequest.of(0, properties.anchorAuditBatchSize()))) {
            if (!net.deploymentId().equalsIgnoreCase(pending.getDeploymentId())) {
                submitReanchor(pending.getTradeId());
                checked++;
            }
        }
        List<TradeTransaction> batch = tradeRepository.findForAnchorAudit(TradeStatus.CONFIRMED, PageRequest.of(0, properties.anchorAuditBatchSize()));
        for (TradeTransaction candidate : batch) {
            try {
                checkAnchor(gateway, net, candidate.getTradeId());
                checked++;
            } catch (IOException e) {
                log.warn("Anchor audit interrupted by an RPC error: {}", e.getMessage());
                break;
            }
        }
        return checked;
    }

    /** @return null when the anchor is verified, otherwise the problem (also raised as an alert) */
    public String checkAnchor(BlockchainGateway gateway, NetworkStatus net, String tradeId) throws IOException {
        Snapshot snap = snapshot(tradeId);
        String problem = null;
        String type = BlockchainAlertService.ANCHOR_INVALID;
        if (snap.deploymentId() != null && deploymentChanged(snap, net)) {
            problem = "anchored on deployment " + snap.deploymentId() + ", current deployment is " + net.deploymentId();
            type = BlockchainAlertService.ANCHOR_MISSING;
        } else {
            Optional<OnChainTrade> onChain = gateway.getTrade(snap.payload().tradeKey());
            if (onChain.isEmpty()) {
                problem = "no on-chain record on the current deployment";
                type = BlockchainAlertService.ANCHOR_MISSING;
            } else if (!onChain.get().validAnchor()) {
                problem = "on-chain record is VOIDED";
            } else if (compare(onChain.get(), snap.payload(), snap.refsStored()) == Match.MISMATCH) {
                problem = "on-chain record differs from the database record";
            } else {
                Optional<ReceiptInfo> receipt = snap.txHash() == null ? Optional.empty() : gateway.receipt(snap.txHash());
                if (receipt.isEmpty() || !receipt.get().success()
                        || !receipt.get().recordedByContract(net.contractAddress(), snap.payload().tradeKey())) {
                    problem = "the stored transaction receipt does not show TradeRecorded for this trade on the current contract";
                }
            }
        }
        String detected = problem;
        transactionTemplate.executeWithoutResult(tx -> tradeRepository.lockByTradeId(tradeId).ifPresent(t -> {
            t.setAnchorCheckedAt(LocalDateTime.now(clock));
            if (detected == null && t.getDeploymentId() == null) {
                // Pre-V003 confirmed trade: the verified record establishes its deployment.
                t.setDeploymentId(net.deploymentId());
                t.setChainGenesisHash(net.genesisHash());
            }
            tradeRepository.save(t);
        }));
        if (problem == null) {
            alertService.resolveForTrade(tradeId, "SYSTEM", "anchor verified on deployment " + net.deploymentId());
        } else {
            alertService.raise(type, "ANCHOR:" + tradeId, tradeId, "tradeId=" + tradeId + ": " + problem, "BLOCKCHAIN_ANCHOR");
        }
        return problem;
    }

    // ================================================================== verification endpoint

    /** MATCH, MISMATCH, UNVERIFIABLE, VOIDED, NOT_ON_CHAIN, DEPLOYMENT_CHANGED, UNAVAILABLE or BLOCKCHAIN_DISABLED. */
    public String verifyOnChain(String tradeId) throws IOException {
        BlockchainGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            return "BLOCKCHAIN_DISABLED";
        }
        NetworkStatus net = verifiedNetwork(gateway, true);
        if (net == null) {
            return "UNAVAILABLE";
        }
        Snapshot snap = snapshot(tradeId);
        if (snap.deploymentId() != null && deploymentChanged(snap, net)) {
            return "DEPLOYMENT_CHANGED";
        }
        Optional<OnChainTrade> onChain = gateway.getTrade(snap.payload().tradeKey());
        if (onChain.isEmpty()) {
            return "NOT_ON_CHAIN";
        }
        if (!onChain.get().validAnchor()) {
            return "VOIDED";
        }
        return compare(onChain.get(), snap.payload(), snap.refsStored()).name();
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<SubmissionAttemptView> attempts(String tradeId) {
        TradeTransaction t = tradeRepository.findByTradeId(tradeId).orElseThrow();
        return attemptRepository.findByTradeIdOrderByAttemptNoAsc(t.getId()).stream().map(a -> new SubmissionAttemptView(
                a.getAttemptNo(), a.getPurpose().name(), a.getTxHash(), a.getNonce(), a.getChainId(), a.getContractAddress(),
                a.getDeploymentId(), a.getChainGenesisHash(), a.getSignerAddress(), a.getLeaseToken(), a.getStatus().name(),
                a.getBlockNumber(), a.getDetail(), a.getCreatedAt(), a.getUpdatedAt())).toList();
    }

    // ================================================================== internals

    /** Moves a PENDING or SUBMITTED trade to NEEDS_REVIEW (reservation kept); alerts and audits once. */
    Outcome toReview(String tradeId, String reason) {
        Boolean moved = transactionTemplate.execute(tx -> {
            TradeTransaction t = tradeRepository.lockByTradeId(tradeId).orElseThrow();
            if (t.getStatus() != TradeStatus.PENDING && t.getStatus() != TradeStatus.SUBMITTED) {
                return false;
            }
            t.setReviewReason(truncate(reason));
            t.setRejectionReason(USER_REVIEW);
            tradeStateService.transition(t, TradeStatus.NEEDS_REVIEW, "SYSTEM", "held for administrator review");
            return true;
        });
        if (Boolean.TRUE.equals(moved)) {
            alertService.raise(BlockchainAlertService.NEEDS_REVIEW, "REVIEW:" + tradeId, tradeId,
                    "tradeId=" + tradeId + ": " + reason, "BLOCKCHAIN_RECONCILE");
            log.warn("Trade {} needs administrator review: {}", tradeId, reason);
        }
        return Outcome.NEEDS_REVIEW;
    }

    /**
     * The current attempt was not (or can no longer be) mined. With {@code keepNonce} the next attempt
     * reuses the same nonce, so the dropped attempt and the retry can never both be mined.
     */
    private Outcome notAccepted(String tradeId, String txHash, boolean keepNonce) {
        Outcome outcome = transactionTemplate.execute(tx -> {
            TradeTransaction t = tradeRepository.lockByTradeId(tradeId).orElseThrow();
            if (t.getStatus() != TradeStatus.SUBMITTED || !txHash.equalsIgnoreCase(t.getTxHash())) {
                return Outcome.SKIPPED;
            }
            if (!keepNonce && t.getSubmissionAttempts() >= properties.maxSubmissionAttempts()) {
                tradeStateService.release(t, TradeStatus.FAILED, ACTOR,
                        "Blockchain submission failed after " + t.getSubmissionAttempts() + " attempts");
                return Outcome.FAILED;
            }
            t.setTxHash(null);
            if (!keepNonce) {
                t.setTxNonce(null);
            }
            tradeStateService.transition(t, TradeStatus.PENDING, ACTOR, keepNonce
                    ? "not seen by the blockchain node; will resubmit with the same nonce"
                    : "not accepted by the blockchain node; will retry");
            return Outcome.RETRY;
        });
        auditService.record("SYSTEM", "BLOCKCHAIN_SUBMIT", String.valueOf(outcome), "tradeId=" + tradeId);
        return outcome;
    }

    private void recordAdoptedAttempt(String tradeId, NetworkStatus net, Evidence ev) {
        transactionTemplate.executeWithoutResult(tx -> {
            Optional<TradeSubmissionAttempt> known = attemptRepository.findByTxHashIgnoreCase(ev.eventTxHash());
            if (known.isPresent()) {
                known.get().setStatus(Status.MINED_SUCCESS);
                known.get().setBlockNumber(ev.eventBlock());
                attemptRepository.save(known.get());
                return;
            }
            TradeTransaction t = tradeRepository.findByTradeId(tradeId).orElseThrow();
            TradeSubmissionAttempt adopted = new TradeSubmissionAttempt(t, (int) attemptRepository.countByTradeId(t.getId()) + 1,
                    Purpose.ADOPTED, ev.eventTxHash(), -1, net.actualChainId(), net.contractAddress(), net.deploymentId(),
                    net.genesisHash(), net.signerAddress(), 0, Status.MINED_SUCCESS,
                    "transaction found on-chain (not in the local attempt history)");
            adopted.setBlockNumber(ev.eventBlock());
            attemptRepository.save(adopted);
        });
    }

    /**
     * Marks attempts that are still open on other deployments as ABANDONED once an administrator has
     * attested that those deployments are retired, so the history does not read as still in flight.
     */
    public void retireAttemptsOnOtherDeployments(String tradeId, String currentDeploymentId, String adminCode) {
        transactionTemplate.executeWithoutResult(tx -> tradeRepository.findByTradeId(tradeId).ifPresent(t ->
                attemptRepository.findByTradeIdOrderByAttemptNoAsc(t.getId()).stream()
                        .filter(a -> !a.getDeploymentId().equalsIgnoreCase(currentDeploymentId)
                                && EnumSet.of(Status.SIGNED, Status.BROADCAST, Status.UNCERTAIN).contains(a.getStatus()))
                        .forEach(a -> {
                            a.setStatus(Status.ABANDONED);
                            a.setDetail("deployment retired (attested by " + adminCode + "); outcome there not established");
                            attemptRepository.save(a);
                        })));
    }

    /**
     * True while an earlier re-anchor attempt on this deployment is still known to the node. Attempts
     * the node no longer knows are marked DROPPED; a fresh re-anchor is then safe because the trade is
     * not recorded on this deployment and the contract rejects a second record of the same trade key.
     */
    private boolean reanchorInFlight(BlockchainGateway gateway, Long tradeDbId, NetworkStatus net) throws IOException {
        boolean inFlight = false;
        for (TradeSubmissionAttempt a : attemptRepository.findByTradeIdOrderByAttemptNoAsc(tradeDbId)) {
            if (a.getPurpose() != Purpose.REANCHOR || !a.getDeploymentId().equalsIgnoreCase(net.deploymentId())
                    || !EnumSet.of(Status.SIGNED, Status.BROADCAST, Status.UNCERTAIN).contains(a.getStatus())) {
                continue;
            }
            if (gateway.isTransactionKnown(a.getTxHash())) {
                inFlight = true;
            } else if (Duration.between(a.getCreatedAt(), LocalDateTime.now(clock)).compareTo(properties.unknownTransactionGrace()) < 0) {
                inFlight = true; // too early to conclude
            } else {
                updateAttempt(a.getTxHash(), Status.DROPPED, null, "re-anchor transaction unknown to the node");
            }
        }
        return inFlight;
    }

    private void updateAttempt(String hash, Status status, Long block, String detail) {
        transactionTemplate.executeWithoutResult(tx -> attemptRepository.findByTxHashIgnoreCase(hash).ifPresent(a -> {
            a.setStatus(status);
            if (block != null) {
                a.setBlockNumber(block);
            }
            a.setDetail(detail);
            attemptRepository.save(a);
        }));
    }

    private static boolean deploymentChanged(Snapshot snap, NetworkStatus net) {
        if (snap.deploymentId() != null && !snap.deploymentId().equalsIgnoreCase(net.deploymentId())) {
            return true;
        }
        return snap.genesisHash() != null && net.genesisHash() != null && !snap.genesisHash().equalsIgnoreCase(net.genesisHash());
    }

    private Optional<ReceiptInfo> awaitReceipt(BlockchainGateway gateway, String hash) {
        Instant deadline = Instant.now().plus(properties.receiptTimeout());
        while (true) {
            try {
                Optional<ReceiptInfo> receipt = gateway.receipt(hash);
                if (receipt.isPresent()) {
                    return receipt;
                }
            } catch (IOException e) {
                log.debug("Receipt lookup failed for {}: {}", hash, e.getMessage());
            }
            if (Instant.now().isAfter(deadline)) {
                return Optional.empty();
            }
            try {
                Thread.sleep(properties.receiptPollInterval().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    /**
     * Verified network status; null on failure, with a de-duplicated alert. {@code fresh} is used by
     * every path that can sign, reconcile or change a trade, so a chain reset is never hidden by a
     * cached result; only the per-cycle availability gate may reuse the short-lived cache.
     */
    private NetworkStatus verifiedNetwork(BlockchainGateway gateway, boolean fresh) {
        Instant now = Instant.now();
        NetworkStatus cached = verified;
        if (!fresh && cached != null && verifiedAt != null && now.isBefore(verifiedAt.plus(properties.verificationCacheTtl()))) {
            return cached;
        }
        NetworkStatus status;
        try {
            status = gateway.verifyNetwork();
        } catch (IOException e) {
            verified = null;
            alertService.raise(BlockchainAlertService.NETWORK, "NETWORK:RPC_UNAVAILABLE", null,
                    "blockchain RPC unavailable", "BLOCKCHAIN_VERIFY");
            return null;
        }
        if (!status.ok()) {
            verified = null;
            alertService.raise(BlockchainAlertService.NETWORK, "NETWORK:" + status.problemCode(), null,
                    status.problem(), "BLOCKCHAIN_VERIFY");
            return null;
        }
        boolean wasUnverified = cached == null;
        verified = status;
        verifiedAt = now;
        if (wasUnverified) {
            alertService.resolveAllOfType(BlockchainAlertService.NETWORK, "SYSTEM", "network verified");
        }
        return status;
    }

    /** Compares amounts, digest and party references; references not stored at reservation are UNVERIFIABLE. */
    static Match compare(OnChainTrade onChain, TradeRecordPayload p, boolean refsStored) {
        boolean core = onChain.quantityWh() == p.quantityWh() && onChain.pricePaisePerKwh() == p.pricePaisePerKwh()
                && onChain.totalPaise().equals(p.totalPaise()) && Arrays.equals(onChain.recordDigest(), p.recordDigest());
        boolean refs = Arrays.equals(onChain.sellerRef(), p.sellerRef()) && Arrays.equals(onChain.buyerRef(), p.buyerRef());
        if (core && refs) {
            return Match.MATCH;
        }
        if (core && !refsStored) {
            return Match.UNVERIFIABLE;
        }
        return Match.MISMATCH;
    }

    private record Snapshot(Long id, TradeStatus status, String txHash, Long txNonce, LocalDateTime submittedAt,
                            String deploymentId, String genesisHash, String reanchorDeploymentId,
                            TradeRecordPayload payload, boolean refsStored) {
    }

    private Snapshot snapshot(String tradeId) {
        return transactionTemplate.execute(tx -> tradeRepository.findByTradeId(tradeId)
                .map(t -> new Snapshot(t.getId(), t.getStatus(), t.getTxHash(), t.getTxNonce(), t.getSubmittedAt(),
                        t.getDeploymentId(), t.getChainGenesisHash(), t.getReanchorDeploymentId(),
                        chainMapper.payload(t, t.getSeller().getHouseholdCode(), t.getBuyer().getHouseholdCode()),
                        chainMapper.refsStored(t)))
                .orElse(null));
    }

    private boolean snapshotRefsStored(String tradeId) {
        Snapshot s = snapshot(tradeId);
        return s != null && s.refsStored();
    }

    private static String truncate(String text) {
        return text != null && text.length() > 255 ? text.substring(0, 255) : text;
    }
}
