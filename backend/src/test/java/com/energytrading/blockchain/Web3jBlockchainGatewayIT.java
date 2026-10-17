package com.energytrading.blockchain;

import com.energytrading.blockchain.BlockchainGateway.BroadcastResult;
import com.energytrading.blockchain.BlockchainGateway.NetworkStatus;
import com.energytrading.blockchain.BlockchainGateway.ReceiptInfo;
import com.energytrading.blockchain.BlockchainGateway.SignedTransaction;
import com.energytrading.blockchain.BlockchainGateway.TradeRecordPayload;
import com.energytrading.config.BlockchainProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Live test of {@link Web3jBlockchainGateway} against a running Hardhat node with EnergyTrading
 * deployed (skipped unless RUN_BLOCKCHAIN_IT=true). Requires BLOCKCHAIN_RPC_URL, BLOCKCHAIN_CHAIN_ID,
 * CONTRACT_ADDRESS and BLOCKCHAIN_SIGNER_PRIVATE_KEY (an authorized recorder) in the environment.
 */
@EnabledIfEnvironmentVariable(named = "RUN_BLOCKCHAIN_IT", matches = "true")
class Web3jBlockchainGatewayIT {

    private static Web3jBlockchainGateway gateway;

    static BlockchainProperties properties(String rpcUrl) {
        return new BlockchainProperties(true, rpcUrl, Long.parseLong(System.getenv().getOrDefault("BLOCKCHAIN_CHAIN_ID", "31337")),
                System.getenv("CONTRACT_ADDRESS"), System.getenv("BLOCKCHAIN_DEPLOYMENT_ID"), System.getenv("BLOCKCHAIN_SIGNER_PRIVATE_KEY"),
                500_000, Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofMillis(200), 3, Duration.ofSeconds(60),
                Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofSeconds(2), 200);
    }

    @BeforeAll
    static void connect() {
        gateway = new Web3jBlockchainGateway(properties(System.getenv().getOrDefault("BLOCKCHAIN_RPC_URL", "http://127.0.0.1:8545")));
    }

    @AfterAll
    static void close() {
        gateway.close();
    }

    private static byte[] sha256(String text) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    }

    private static TradeRecordPayload payload(String tradeId, long quantityWh) throws Exception {
        long price = 650;
        return new TradeRecordPayload(sha256("energy-trade|" + tradeId), sha256("seller|" + tradeId), sha256("buyer|" + tradeId),
                quantityWh, price, BigInteger.valueOf((quantityWh * price + 500) / 1000), sha256("digest|" + tradeId));
    }

    private static ReceiptInfo submitAndWait(TradeRecordPayload p) throws Exception {
        SignedTransaction tx = gateway.signRecordTrade(p, gateway.pendingNonce());
        BroadcastResult result = gateway.broadcast(tx);
        for (int i = 0; i < 50; i++) {
            Optional<ReceiptInfo> receipt = gateway.receipt(tx.hash());
            if (receipt.isPresent()) {
                assertThat(receipt.get().transactionHash()).isEqualToIgnoringCase(tx.hash());
                return receipt.get();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no receipt for " + tx.hash() + " (broadcast " + result + ")");
    }

    @Test
    @DisplayName("Network verification: chain ID, deployed contract, version and authorized signer")
    void verifiesNetwork() throws Exception {
        NetworkStatus status = gateway.verifyNetwork();
        assertThat(status.problem()).isNull();
        assertThat(status.ok()).isTrue();
        assertThat(status.contractVersion()).isEqualTo("EnergyTrading/2");
        assertThat(status.deploymentId()).isEqualToIgnoringCase(System.getenv("BLOCKCHAIN_DEPLOYMENT_ID"));
        assertThat(status.genesisHash()).matches("0x[0-9a-f]{64}");
        assertThat(status.latestBlock()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Successful recordTrade: locally computed hash, receipt status 1, on-chain record and event log")
    void recordsTrade() throws Exception {
        TradeRecordPayload p = payload("IT-" + UUID.randomUUID(), 2500);
        assertThat(gateway.isTradeRecorded(p.tradeKey())).isFalse();

        ReceiptInfo receipt = submitAndWait(p);
        assertThat(receipt.success()).isTrue();
        assertThat(receipt.blockNumber()).isPositive();
        assertThat(gateway.isTransactionKnown(receipt.transactionHash())).isTrue();
        assertThat(gateway.isTradeRecorded(p.tradeKey())).isTrue();

        var onChain = gateway.getTrade(p.tradeKey()).orElseThrow();
        assertThat(onChain.quantityWh()).isEqualTo(2500);
        assertThat(onChain.pricePaisePerKwh()).isEqualTo(650);
        assertThat(onChain.totalPaise()).isEqualTo(BigInteger.valueOf(1625));
        assertThat(onChain.recordDigest()).isEqualTo(p.recordDigest());
        assertThat(onChain.sellerRef()).isEqualTo(p.sellerRef());
        assertThat(onChain.status()).isEqualTo(1);

        assertThat(receipt.to()).isEqualToIgnoringCase(System.getenv("CONTRACT_ADDRESS"));
        assertThat(receipt.recordedByContract(System.getenv("CONTRACT_ADDRESS"), p.tradeKey()))
                .as("receipt carries TradeRecorded for this trade key from the contract").isTrue();
        var log = gateway.findTradeRecordedLog(p.tradeKey()).orElseThrow();
        assertThat(log.transactionHash()).isEqualToIgnoringCase(receipt.transactionHash());
        assertThat(log.blockNumber()).isEqualTo(receipt.blockNumber());
    }

    @Test
    @DisplayName("Duplicate trade key: the contract reverts and the original record is unchanged")
    void duplicateIsReverted() throws Exception {
        TradeRecordPayload p = payload("IT-" + UUID.randomUUID(), 1000);
        assertThat(submitAndWait(p).success()).isTrue();
        var original = gateway.findTradeRecordedLog(p.tradeKey()).orElseThrow();

        SignedTransaction replay = gateway.signRecordTrade(p, gateway.pendingNonce());
        BroadcastResult result = gateway.broadcast(replay);
        Optional<ReceiptInfo> receipt = gateway.receipt(replay.hash());
        // Hardhat answers a reverting transaction with an error but still mines it with status 0.
        assertThat(result.kind() == BroadcastResult.Kind.REJECTED || receipt.map(r -> !r.success()).orElse(false)).isTrue();
        receipt.ifPresent(r -> assertThat(r.success()).isFalse());
        assertThat(gateway.findTradeRecordedLog(p.tradeKey()).orElseThrow().transactionHash()).isEqualTo(original.transactionHash());
    }

    @Test
    @DisplayName("Invalid payload (zero quantity) reverts on-chain and records nothing")
    void invalidPayloadReverts() throws Exception {
        TradeRecordPayload valid = payload("IT-" + UUID.randomUUID(), 1000);
        TradeRecordPayload zero = new TradeRecordPayload(valid.tradeKey(), valid.sellerRef(), valid.buyerRef(), 0, 650,
                BigInteger.ZERO, valid.recordDigest());
        SignedTransaction tx = gateway.signRecordTrade(zero, gateway.pendingNonce());
        BroadcastResult result = gateway.broadcast(tx);
        assertThat(result.kind()).isEqualTo(BroadcastResult.Kind.REJECTED);
        assertThat(result.message()).contains("InvalidQuantity");
        gateway.receipt(tx.hash()).ifPresent(r -> assertThat(r.success()).isFalse());
        assertThat(gateway.isTradeRecorded(zero.tradeKey())).isFalse();
    }

    @Test
    @DisplayName("Unknown transaction hashes are reported as unknown")
    void unknownTransaction() throws Exception {
        String unknown = "0x" + "12".repeat(32);
        assertThat(gateway.isTransactionKnown(unknown)).isFalse();
        assertThat(gateway.receipt(unknown)).isEmpty();
    }

    @Test
    @DisplayName("RPC failure: calls fail with IOException and broadcast reports an UNKNOWN outcome (never a failure)")
    void rpcFailure() throws Exception {
        try (Web3jBlockchainGateway down = new Web3jBlockchainGateway(properties("http://127.0.0.1:1"))) {
            assertThatThrownBy(down::verifyNetwork).isInstanceOf(IOException.class);
            SignedTransaction tx = gateway.signRecordTrade(payload("IT-" + UUID.randomUUID(), 1000), BigInteger.valueOf(999_999));
            assertThat(down.broadcast(tx).kind()).isEqualTo(BroadcastResult.Kind.UNKNOWN);
        }
    }

    @Test
    @DisplayName("Invalid configuration fails fast with the variable name, never the secret value")
    void invalidConfiguration() {
        BlockchainProperties bad = new BlockchainProperties(true, "ftp://x", 0, "0x123", "0x1", "not-a-key", 500_000,
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMillis(100), 3, Duration.ofSeconds(60), Duration.ofSeconds(60),
                Duration.ofSeconds(30), Duration.ofSeconds(2), 200);
        assertThatThrownBy(() -> new Web3jBlockchainGateway(bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BLOCKCHAIN_RPC_URL").hasMessageContaining("CONTRACT_ADDRESS")
                .hasMessageContaining("BLOCKCHAIN_SIGNER_PRIVATE_KEY").hasMessageContaining("BLOCKCHAIN_DEPLOYMENT_ID")
                .hasMessageNotContaining("not-a-key");
    }
}
