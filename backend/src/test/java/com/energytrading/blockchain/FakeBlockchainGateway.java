package com.energytrading.blockchain;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * In-memory stand-in for a local node and the EnergyTrading contract, with switchable failure
 * modes. It models deployments (deploymentId + genesis hash), chain resets, redeployments,
 * contract statuses (Recorded/Settled/Voided), receipts with target and events, and nonces.
 */
public class FakeBlockchainGateway implements BlockchainGateway {

    public enum Mode {
        /** Accepted and mined immediately with status 1. */
        SUCCESS,
        /** Mined immediately with status 0 (reverted). */
        REVERT,
        /** Node answers with an error and never sees the transaction. */
        NODE_REJECTS,
        /** No response, but the transaction was mined successfully. */
        UNCERTAIN_MINED,
        /** No response and the transaction never reached the node. */
        UNCERTAIN_LOST,
        /** Accepted, but stays pending (no receipt) until {@link #mineAllPending()}. */
        PENDING_FOREVER
    }

    /** How a successful receipt is reported (to simulate inconsistent evidence). */
    public enum ReceiptStyle { NORMAL, WRONG_TARGET, NO_EVENT, OTHER_TRADE_EVENT }

    public static final long CHAIN_ID = 31337;
    public static final String CONTRACT = "0x5FbDB2315678afecb367f032d93F642f64180aa3";
    public static final String SIGNER = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    private static final class Deployment {
        final String address;
        final String id;
        final Map<String, OnChainTrade> trades = new ConcurrentHashMap<>();
        final Map<String, RecordedLog> logs = new ConcurrentHashMap<>();

        Deployment(String address) {
            this.address = address;
            this.id = randomHash();
        }
    }

    public volatile Mode mode = Mode.SUCCESS;
    public volatile ReceiptStyle receiptStyle = ReceiptStyle.NORMAL;
    public volatile boolean rpcDown = false;
    public volatile long reportedChainId = CHAIN_ID;
    public volatile boolean signerAuthorized = true;
    public volatile long broadcastDelayMs = 0;
    /** Number of upcoming receipt lookups that fail with an IOException (application "timeout"). */
    public final AtomicInteger receiptFailures = new AtomicInteger();
    public final AtomicInteger broadcasts = new AtomicInteger();
    public final AtomicLong blockNumber = new AtomicLong(100);
    /** Called with the transaction hash at the moment of broadcast (to inspect the database then). */
    public volatile Consumer<String> onBroadcast = hash -> { };
    /** Called with the transaction hash right after signing, before the write-ahead step. */
    public volatile Consumer<String> onSign = hash -> { };

    private volatile String genesis;
    private volatile Deployment current;
    private volatile String configuredDeploymentId;
    private final Map<String, ReceiptInfo> receipts = new ConcurrentHashMap<>();
    private final Map<String, TradeRecordPayload> pending = new ConcurrentHashMap<>();
    private final Map<String, TradeRecordPayload> signedPayloads = new ConcurrentHashMap<>();
    private final Map<String, Long> signedNonces = new ConcurrentHashMap<>();
    private final AtomicLong minedNonce = new AtomicLong();
    private final List<String> broadcastHashes = new ArrayList<>();

    public FakeBlockchainGateway() {
        reset();
    }

    public synchronized void reset() {
        mode = Mode.SUCCESS;
        receiptStyle = ReceiptStyle.NORMAL;
        rpcDown = false;
        reportedChainId = CHAIN_ID;
        signerAuthorized = true;
        broadcastDelayMs = 0;
        receiptFailures.set(0);
        broadcasts.set(0);
        onBroadcast = hash -> { };
        onSign = hash -> { };
        receipts.clear();
        pending.clear();
        signedPayloads.clear();
        signedNonces.clear();
        minedNonce.set(0);
        synchronized (broadcastHashes) {
            broadcastHashes.clear();
        }
        genesis = randomHash();
        current = new Deployment(CONTRACT);
        configuredDeploymentId = current.id;
    }

    // ------------------------------------------------------------------ scenario controls

    /** Hardhat restart: new genesis, empty chain, contract redeployed at the SAME address with a new ID. */
    public synchronized void resetChainAndRedeploySameAddress() {
        genesis = randomHash();
        receipts.clear();
        pending.clear();
        minedNonce.set(0);
        current = new Deployment(CONTRACT);
    }

    /** Redeployment at a different address on the same chain (old receipts remain). */
    public synchronized String redeployAtNewAddress() {
        String address = "0x" + randomHash().substring(2, 42);
        current = new Deployment(address);
        return address;
    }

    /** The operator updates BLOCKCHAIN_DEPLOYMENT_ID / CONTRACT_ADDRESS to the current deployment. */
    public void acceptCurrentDeploymentInConfiguration() {
        configuredDeploymentId = current.id;
    }

    public String currentDeploymentId() {
        return current.id;
    }

    public void voidOnChain(byte[] tradeKey) {
        current.trades.computeIfPresent(key(tradeKey), (k, t) -> withStatus(t, OnChainTrade.VOIDED));
    }

    public void settleOnChain(byte[] tradeKey) {
        current.trades.computeIfPresent(key(tradeKey), (k, t) -> withStatus(t, OnChainTrade.SETTLED));
    }

    public void removeEventLog(byte[] tradeKey) {
        current.logs.remove(key(tradeKey));
    }

    public int recordedTradeCount() {
        return current.trades.size();
    }

    public List<String> broadcastHashes() {
        synchronized (broadcastHashes) {
            return List.copyOf(broadcastHashes);
        }
    }

    /** Records a trade directly (as if an earlier, forgotten transaction succeeded). */
    public String recordOutOfBand(TradeRecordPayload p) {
        String hash = randomHash();
        mine(hash, p, ReceiptStyle.NORMAL);
        return hash;
    }

    /** Stores a different record under the key (simulates a mismatching on-chain record). */
    public void recordTampered(TradeRecordPayload p) {
        TradeRecordPayload tampered = new TradeRecordPayload(p.tradeKey(), p.sellerRef(), p.buyerRef(), p.quantityWh() + 1,
                p.pricePaisePerKwh(), p.totalPaise(), p.recordDigest());
        current.trades.put(key(p.tradeKey()), toOnChain(tampered, OnChainTrade.RECORDED));
    }

    /** Records the trade with different party references (as produced by a rotated key). */
    public void recordWithOtherRefs(TradeRecordPayload p) {
        TradeRecordPayload other = new TradeRecordPayload(p.tradeKey(), randomBytes(), randomBytes(), p.quantityWh(),
                p.pricePaisePerKwh(), p.totalPaise(), p.recordDigest());
        mine(randomHash(), other, ReceiptStyle.NORMAL);
    }

    public void mineAllPending() {
        pending.forEach((hash, p) -> {
            minedNonce.incrementAndGet();
            mine(hash, p, receiptStyle);
        });
        pending.clear();
    }

    // ------------------------------------------------------------------ gateway

    @Override
    public long chainId() {
        return CHAIN_ID;
    }

    @Override
    public String contractAddress() {
        return current.address;
    }

    @Override
    public String signerAddress() {
        return SIGNER;
    }

    @Override
    public NetworkStatus verifyNetwork() throws IOException {
        down();
        return new NetworkStatus(CHAIN_ID, reportedChainId, current.address, true, NetworkStatus.EXPECTED_VERSION, SIGNER,
                signerAuthorized, blockNumber.get(), configuredDeploymentId, current.id, genesis);
    }

    @Override
    public boolean isTradeRecorded(byte[] tradeKey) throws IOException {
        down();
        return current.trades.containsKey(key(tradeKey));
    }

    @Override
    public Optional<OnChainTrade> getTrade(byte[] tradeKey) throws IOException {
        down();
        return Optional.ofNullable(current.trades.get(key(tradeKey)));
    }

    @Override
    public Optional<RecordedLog> findTradeRecordedLog(byte[] tradeKey) throws IOException {
        down();
        return Optional.ofNullable(current.logs.get(key(tradeKey)));
    }

    @Override
    public BigInteger pendingNonce() throws IOException {
        down();
        return BigInteger.valueOf(minedNonce.get() + pending.size());
    }

    @Override
    public BigInteger latestNonce() throws IOException {
        down();
        return BigInteger.valueOf(minedNonce.get());
    }

    @Override
    public SignedTransaction signRecordTrade(TradeRecordPayload payload, BigInteger n) throws IOException {
        down();
        // Deterministic like RFC 6979 ECDSA: same payload, nonce and deployment give the same hash.
        String hash = "0x" + String.format("%064x", new BigInteger(1, payload.tradeKey())
                .xor(BigInteger.valueOf(n.longValue() + 1)).xor(new BigInteger(1, HexFormat.of().parseHex(current.id.substring(2)))));
        signedPayloads.put(hash, payload);
        signedNonces.put(hash, n.longValue());
        onSign.accept(hash);
        return new SignedTransaction(hash, "0xraw" + hash.substring(2), n);
    }

    @Override
    public BroadcastResult broadcast(SignedTransaction tx) {
        onBroadcast.accept(tx.hash());
        if (broadcastDelayMs > 0) {
            try {
                Thread.sleep(broadcastDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (rpcDown) {
            return BroadcastResult.unknown("ConnectException: connection refused");
        }
        broadcasts.incrementAndGet();
        synchronized (broadcastHashes) {
            broadcastHashes.add(tx.hash());
        }
        TradeRecordPayload p = signedPayloads.get(tx.hash());
        long nonce = signedNonces.get(tx.hash());
        if (mode != Mode.NODE_REJECTS && mode != Mode.UNCERTAIN_LOST && nonce < minedNonce.get()) {
            return BroadcastResult.rejected("nonce too low");
        }
        switch (mode) {
            case SUCCESS -> {
                minedNonce.incrementAndGet();
                if (current.trades.containsKey(key(p.tradeKey()))) {
                    revert(tx.hash());
                    return BroadcastResult.rejected("reverted with custom error 'TradeAlreadyRecorded'");
                }
                mine(tx.hash(), p, receiptStyle);
                return BroadcastResult.accepted();
            }
            case REVERT -> {
                minedNonce.incrementAndGet();
                revert(tx.hash());
                return BroadcastResult.rejected("VM Exception: reverted with custom error 'InvalidQuantity()'");
            }
            case NODE_REJECTS -> {
                return BroadcastResult.rejected("nonce too low");
            }
            case UNCERTAIN_MINED -> {
                minedNonce.incrementAndGet();
                mine(tx.hash(), p, receiptStyle);
                return BroadcastResult.unknown("SocketTimeoutException: timeout");
            }
            case UNCERTAIN_LOST -> {
                return BroadcastResult.unknown("SocketTimeoutException: timeout");
            }
            case PENDING_FOREVER -> {
                pending.put(tx.hash(), p);
                return BroadcastResult.accepted();
            }
            default -> throw new IllegalStateException();
        }
    }

    @Override
    public Optional<ReceiptInfo> receipt(String transactionHash) throws IOException {
        down();
        if (receiptFailures.get() > 0) {
            receiptFailures.decrementAndGet();
            throw new IOException("SocketTimeoutException: receipt lookup timed out");
        }
        return Optional.ofNullable(receipts.get(transactionHash.toLowerCase()));
    }

    @Override
    public boolean isTransactionKnown(String transactionHash) throws IOException {
        down();
        return receipts.containsKey(transactionHash.toLowerCase()) || pending.containsKey(transactionHash);
    }

    // ------------------------------------------------------------------ internals

    private void mine(String hash, TradeRecordPayload p, ReceiptStyle style) {
        long block = blockNumber.incrementAndGet();
        Deployment d = current;
        d.trades.put(key(p.tradeKey()), toOnChain(p, OnChainTrade.RECORDED));
        d.logs.put(key(p.tradeKey()), new RecordedLog(hash, block));
        String to = style == ReceiptStyle.WRONG_TARGET ? "0x" + "99".repeat(20) : d.address;
        List<String> events = switch (style) {
            case NO_EVENT -> List.of();
            case OTHER_TRADE_EVENT -> List.of(key(randomBytes()));
            default -> List.of(key(p.tradeKey()));
        };
        receipts.put(hash.toLowerCase(), new ReceiptInfo(hash, block, true, null, to, events));
    }

    private void revert(String hash) {
        receipts.put(hash.toLowerCase(), new ReceiptInfo(hash, blockNumber.incrementAndGet(), false, "reverted",
                current.address, List.of()));
    }

    private void down() throws IOException {
        if (rpcDown) {
            throw new IOException("ConnectException: connection refused");
        }
    }

    private static OnChainTrade toOnChain(TradeRecordPayload p, int status) {
        return new OnChainTrade(p.sellerRef(), p.buyerRef(), p.quantityWh(), p.pricePaisePerKwh(), p.totalPaise(),
                p.recordDigest(), 1, status);
    }

    private static OnChainTrade withStatus(OnChainTrade t, int status) {
        return new OnChainTrade(t.sellerRef(), t.buyerRef(), t.quantityWh(), t.pricePaisePerKwh(), t.totalPaise(),
                t.recordDigest(), t.recordedAt(), status);
    }

    private static String key(byte[] tradeKey) {
        return HexFormat.of().formatHex(tradeKey);
    }

    private static byte[] randomBytes() {
        return HexFormat.of().parseHex(randomHash().substring(2));
    }

    private static String randomHash() {
        return "0x" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }
}
