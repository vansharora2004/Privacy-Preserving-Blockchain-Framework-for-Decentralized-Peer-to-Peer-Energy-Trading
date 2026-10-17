package com.energytrading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Blockchain connector settings ({@code app.blockchain.*}).
 * <p>
 * The signer private key is a secret supplied only through {@code BLOCKCHAIN_SIGNER_PRIVATE_KEY};
 * it is never logged and is redacted from {@link #toString()}. When {@code enabled} is false the
 * backend runs without a blockchain: trades are reserved but never confirmed, and expire.
 *
 * @param gasLimit               gas limit for {@code recordTrade} transactions
 * @param rpcTimeout             connect/read/write timeout of each JSON-RPC call
 * @param receiptTimeout         how long a submission waits for a receipt before leaving the
 *                               trade SUBMITTED for reconciliation
 * @param receiptPollInterval    receipt polling interval
 * @param maxSubmissionAttempts  submissions not accepted by the node are retried this many times
 * @param unknownTransactionGrace a SUBMITTED transaction unknown to the node for longer than this is
 *                               treated as never broadcast (after checking the contract state)
 * @param verificationCacheTtl   how long a successful network/contract verification is reused
 * @param deploymentId           expected contract deploymentId (BLOCKCHAIN_DEPLOYMENT_ID); required when enabled
 * @param signerLeaseTtl         lifetime of the cross-instance signer lease
 * @param submitLockWait         how long a purchase request waits for the submission slot before leaving the trade PENDING
 * @param anchorAuditBatchSize   CONFIRMED trades verified per anchor-audit run
 */
@ConfigurationProperties(prefix = "app.blockchain")
public record BlockchainProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://127.0.0.1:8545") String rpcUrl,
        @DefaultValue("31337") long chainId,
        String contractAddress,
        String deploymentId,
        String signerPrivateKey,
        @DefaultValue("500000") long gasLimit,
        @DefaultValue("10s") Duration rpcTimeout,
        @DefaultValue("15s") Duration receiptTimeout,
        @DefaultValue("500ms") Duration receiptPollInterval,
        @DefaultValue("3") int maxSubmissionAttempts,
        @DefaultValue("60s") Duration unknownTransactionGrace,
        @DefaultValue("60s") Duration verificationCacheTtl,
        @DefaultValue("30s") Duration signerLeaseTtl,
        @DefaultValue("2s") Duration submitLockWait,
        @DefaultValue("200") int anchorAuditBatchSize) {

    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-fA-F]{40}");
    private static final Pattern PRIVATE_KEY = Pattern.compile("(0x)?[0-9a-fA-F]{64}");
    private static final Pattern BYTES32 = Pattern.compile("0x[0-9a-fA-F]{64}");

    /** Returns configuration problems without revealing secret values. Empty when valid. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (rpcUrl == null || !(rpcUrl.startsWith("http://") || rpcUrl.startsWith("https://"))) {
            problems.add("BLOCKCHAIN_RPC_URL must be an http(s) URL");
        }
        if (chainId <= 0) {
            problems.add("BLOCKCHAIN_CHAIN_ID must be a positive integer");
        }
        if (contractAddress == null || !ADDRESS.matcher(contractAddress).matches()) {
            problems.add("CONTRACT_ADDRESS must be a 0x-prefixed 20-byte hex address (see blockchain/deployments/localhost.json)");
        }
        if (deploymentId == null || !BYTES32.matcher(deploymentId).matches()) {
            problems.add("BLOCKCHAIN_DEPLOYMENT_ID must be the 0x-prefixed 32-byte deploymentId printed by npm run deploy:local");
        }
        if (signerPrivateKey == null || signerPrivateKey.isBlank()) {
            problems.add("BLOCKCHAIN_SIGNER_PRIVATE_KEY is required when BLOCKCHAIN_ENABLED=true");
        } else if (!PRIVATE_KEY.matcher(signerPrivateKey.trim()).matches()) {
            problems.add("BLOCKCHAIN_SIGNER_PRIVATE_KEY must be 32 bytes of hex");
        }
        if (gasLimit < 21_000) {
            problems.add("app.blockchain.gas-limit must be at least 21000");
        }
        if (maxSubmissionAttempts < 1) {
            problems.add("app.blockchain.max-submission-attempts must be at least 1");
        }
        if (anchorAuditBatchSize < 1) {
            problems.add("app.blockchain.anchor-audit-batch-size must be at least 1");
        }
        for (Duration d : new Duration[]{rpcTimeout, receiptTimeout, receiptPollInterval, unknownTransactionGrace,
                verificationCacheTtl, signerLeaseTtl, submitLockWait}) {
            if (d == null || d.isNegative() || d.isZero()) {
                problems.add("app.blockchain timeouts and intervals must be positive");
                break;
            }
        }
        return problems;
    }

    @Override
    public String toString() {
        return "BlockchainProperties[enabled=" + enabled + ", rpcUrl=" + rpcUrl + ", chainId=" + chainId
                + ", contractAddress=" + contractAddress + ", deploymentId=" + deploymentId + ", signerPrivateKey=<redacted>]";
    }
}
