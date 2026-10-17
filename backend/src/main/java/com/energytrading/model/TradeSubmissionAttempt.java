package com.energytrading.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * One signed blockchain transaction for a trade. Rows are never deleted or overwritten when a
 * trade is retried, so every earlier hash, nonce and deployment remains available as evidence.
 * Technical details stay here (administrators and auditors only), not in participant views.
 */
@Entity
@Table(name = "trade_submission_attempts", indexes = {
    @Index(name = "uk_attempt_tx_hash", columnList = "tx_hash", unique = true),
    @Index(name = "idx_attempt_trade", columnList = "trade_id, attempt_no"),
    @Index(name = "idx_attempt_deployment", columnList = "deployment_id")
})
public class TradeSubmissionAttempt {

    public enum Purpose { SUBMIT, REANCHOR, ADOPTED }

    public enum Status {
        /** Signed and persisted; not yet broadcast. */
        SIGNED,
        /** Accepted by the node. */
        BROADCAST,
        /** No answer from the node: outcome unknown. */
        UNCERTAIN,
        /** The node refused it; it was never in the node's pool. */
        REJECTED,
        /** Mined with status 1. */
        MINED_SUCCESS,
        /** Mined with status 0. */
        MINED_REVERTED,
        /** Unknown to the node after the grace period on the same deployment. */
        DROPPED,
        /** Signed but abandoned before broadcast (e.g. the signer lease was lost). */
        ABANDONED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trade_id", nullable = false, foreignKey = @ForeignKey(name = "fk_attempt_trade"))
    private TradeTransaction trade;

    @Column(nullable = false)
    private int attemptNo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Purpose purpose;

    @Column(name = "tx_hash", nullable = false, length = 66)
    private String txHash;

    @Column(nullable = false)
    private long nonce;

    @Column(nullable = false)
    private long chainId;

    @Column(nullable = false, length = 42)
    private String contractAddress;

    @Column(name = "deployment_id", nullable = false, length = 66)
    private String deploymentId;

    @Column(length = 66)
    private String chainGenesisHash;

    @Column(nullable = false, length = 42)
    private String signerAddress;

    @Column(nullable = false)
    private long leaseToken;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private Status status;

    @Column
    private Long blockNumber;

    @Column(length = 255)
    private String detail;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    protected TradeSubmissionAttempt() {
    }

    public TradeSubmissionAttempt(TradeTransaction trade, int attemptNo, Purpose purpose, String txHash, long nonce,
                                  long chainId, String contractAddress, String deploymentId, String chainGenesisHash,
                                  String signerAddress, long leaseToken, Status status, String detail) {
        this.trade = trade;
        this.attemptNo = attemptNo;
        this.purpose = purpose;
        this.txHash = txHash;
        this.nonce = nonce;
        this.chainId = chainId;
        this.contractAddress = contractAddress;
        this.deploymentId = deploymentId;
        this.chainGenesisHash = chainGenesisHash;
        this.signerAddress = signerAddress;
        this.leaseToken = leaseToken;
        this.status = status;
        setDetail(detail);
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public TradeTransaction getTrade() { return trade; }
    public int getAttemptNo() { return attemptNo; }
    public Purpose getPurpose() { return purpose; }
    public String getTxHash() { return txHash; }
    public long getNonce() { return nonce; }
    public long getChainId() { return chainId; }
    public String getContractAddress() { return contractAddress; }
    public String getDeploymentId() { return deploymentId; }
    public String getChainGenesisHash() { return chainGenesisHash; }
    public String getSignerAddress() { return signerAddress; }
    public long getLeaseToken() { return leaseToken; }
    public Status getStatus() { return status; }
    public Long getBlockNumber() { return blockNumber; }
    public String getDetail() { return detail; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setStatus(Status status) { this.status = status; }
    public void setBlockNumber(Long blockNumber) { this.blockNumber = blockNumber; }

    public void setDetail(String detail) {
        this.detail = detail != null && detail.length() > 255 ? detail.substring(0, 255) : detail;
    }
}
