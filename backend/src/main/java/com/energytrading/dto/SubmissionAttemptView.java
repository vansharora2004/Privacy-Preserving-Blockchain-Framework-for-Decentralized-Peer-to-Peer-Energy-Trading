package com.energytrading.dto;

import java.time.LocalDateTime;

/** One signed transaction for a trade (administrators and auditors only). */
public record SubmissionAttemptView(int attemptNo, String purpose, String txHash, long nonce, long chainId,
                                    String contractAddress, String deploymentId, String chainGenesisHash,
                                    String signerAddress, long leaseToken, String status, Long blockNumber,
                                    String detail, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
