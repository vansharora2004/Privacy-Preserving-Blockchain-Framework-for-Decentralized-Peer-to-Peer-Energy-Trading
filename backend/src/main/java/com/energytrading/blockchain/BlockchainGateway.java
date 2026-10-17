package com.energytrading.blockchain;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Optional;

/**
 * Narrow interface to the EnergyTrading contract. The production implementation is
 * {@link Web3jBlockchainGateway}; tests substitute a deterministic fake.
 * <p>
 * Transactions are prepared in two steps so that the caller can persist the transaction hash
 * <b>before</b> broadcasting it ({@link #signRecordTrade} then {@link #broadcast}). If the
 * broadcast outcome is unknown, the persisted hash lets reconciliation find the receipt instead
 * of resubmitting; a resubmission reuses the stored nonce while it is unused (see TradeSettlementService).
 */
public interface BlockchainGateway {

    long chainId();

    String contractAddress();

    String signerAddress();

    /** Checks chain ID, deployed code, contract version and signer authorization. */
    NetworkStatus verifyNetwork() throws IOException;

    boolean isTradeRecorded(byte[] tradeKey) throws IOException;

    Optional<OnChainTrade> getTrade(byte[] tradeKey) throws IOException;

    /** The TradeRecorded event for a trade key, if the trade was recorded. */
    Optional<RecordedLog> findTradeRecordedLog(byte[] tradeKey) throws IOException;

    /** Next nonce for the signer, counting pending transactions. */
    BigInteger pendingNonce() throws IOException;

    /** Next nonce for the signer counting only mined transactions (nonces below it are consumed). */
    BigInteger latestNonce() throws IOException;

    SignedTransaction signRecordTrade(TradeRecordPayload payload, BigInteger nonce) throws IOException;

    /** Never throws: a missing response is reported as {@link BroadcastResult.Kind#UNKNOWN}. */
    BroadcastResult broadcast(SignedTransaction transaction);

    Optional<ReceiptInfo> receipt(String transactionHash) throws IOException;

    /** Whether the node knows the transaction (pending or mined). */
    boolean isTransactionKnown(String transactionHash) throws IOException;

    // ------------------------------------------------------------------ value types

    /** Arguments of {@code recordTrade}, already converted to the contract's integer units. */
    record TradeRecordPayload(byte[] tradeKey, byte[] sellerRef, byte[] buyerRef, long quantityWh,
                              long pricePaisePerKwh, BigInteger totalPaise, byte[] recordDigest) {
    }

    record SignedTransaction(String hash, String rawHex, BigInteger nonce) {
        @Override
        public String toString() {
            return "SignedTransaction[hash=" + hash + ", nonce=" + nonce + "]";
        }
    }

    record BroadcastResult(Kind kind, String message) {
        public enum Kind {
            /** The node accepted the transaction into its pool (it may still revert when mined). */
            ACCEPTED,
            /** The node answered with an error; the transaction may or may not have been mined. */
            REJECTED,
            /** No answer (timeout or connection failure): the outcome is uncertain. */
            UNKNOWN
        }

        public static BroadcastResult accepted() {
            return new BroadcastResult(Kind.ACCEPTED, null);
        }

        public static BroadcastResult rejected(String message) {
            return new BroadcastResult(Kind.REJECTED, message);
        }

        public static BroadcastResult unknown(String message) {
            return new BroadcastResult(Kind.UNKNOWN, message);
        }
    }

    /**
     * @param to                 transaction target (the contract that was called)
     * @param recordedTradeKeys  lower-case hex trade keys of TradeRecorded events emitted by the
     *                           configured contract in this receipt
     */
    record ReceiptInfo(String transactionHash, long blockNumber, boolean success, String revertReason,
                       String to, java.util.List<String> recordedTradeKeys) {

        public boolean recordedByContract(String contract, byte[] tradeKey) {
            return to != null && to.equalsIgnoreCase(contract)
                    && recordedTradeKeys.contains(java.util.HexFormat.of().formatHex(tradeKey));
        }
    }

    record RecordedLog(String transactionHash, long blockNumber) {
    }

    /** Status values mirror the contract enum: 0 None, 1 Recorded, 2 Settled, 3 Voided. */
    record OnChainTrade(byte[] sellerRef, byte[] buyerRef, long quantityWh, long pricePaisePerKwh,
                        BigInteger totalPaise, byte[] recordDigest, long recordedAt, int status) {

        public static final int RECORDED = 1;
        public static final int SETTLED = 2;
        public static final int VOIDED = 3;

        /** Recorded and Settled are valid anchors; Voided invalidates the record. */
        public boolean validAnchor() {
            return status == RECORDED || status == SETTLED;
        }
    }

    /**
     * Result of verifying the configured network. A deployment is identified by the contract's
     * immutable {@code deploymentId} (which changes on every redeployment, including a redeployment
     * at the same address on a reset chain) together with the chain's genesis block hash.
     */
    record NetworkStatus(long expectedChainId, long actualChainId, String contractAddress, boolean contractDeployed,
                         String contractVersion, String signerAddress, boolean signerAuthorized, long latestBlock,
                         String expectedDeploymentId, String deploymentId, String genesisHash) {

        public static final String EXPECTED_VERSION = "EnergyTrading/2";

        public boolean ok() {
            return problemCode() == null;
        }

        /** Stable code for alert de-duplication, or null when everything checks out. */
        public String problemCode() {
            if (expectedChainId != actualChainId) {
                return "CHAIN_ID_MISMATCH";
            }
            if (!contractDeployed) {
                return "CONTRACT_NOT_DEPLOYED";
            }
            if (!EXPECTED_VERSION.equals(contractVersion)) {
                return "CONTRACT_VERSION_MISMATCH";
            }
            if (deploymentId == null || !deploymentId.equalsIgnoreCase(expectedDeploymentId)) {
                return "DEPLOYMENT_ID_MISMATCH";
            }
            if (!signerAuthorized) {
                return "SIGNER_NOT_AUTHORIZED";
            }
            return null;
        }

        public String problem() {
            String code = problemCode();
            if (code == null) {
                return null;
            }
            return switch (code) {
                case "CHAIN_ID_MISMATCH" -> "chain ID mismatch: expected " + expectedChainId + ", node reports " + actualChainId;
                case "CONTRACT_NOT_DEPLOYED" -> "no contract code at " + contractAddress + " (deploy with npm run deploy:local)";
                case "CONTRACT_VERSION_MISMATCH" -> "unexpected contract version: " + contractVersion + " (expected " + EXPECTED_VERSION + ")";
                case "DEPLOYMENT_ID_MISMATCH" -> "deployment ID mismatch: the contract at " + contractAddress
                        + " is not the configured deployment (chain reset or redeployment?); update BLOCKCHAIN_DEPLOYMENT_ID only after review";
                default -> "signer " + signerAddress + " is not an authorized recorder";
            };
        }
    }
}
