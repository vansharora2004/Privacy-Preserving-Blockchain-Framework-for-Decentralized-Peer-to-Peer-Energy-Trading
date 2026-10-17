package com.energytrading.model;

import com.energytrading.model.enums.TradeStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "trade_transactions",
    uniqueConstraints = {
        // Idempotent trade requests: one trade per buyer and Idempotency-Key
        @UniqueConstraint(name = "uk_trade_buyer_request", columnNames = {"buyer_id", "client_request_id"})
    },
    indexes = {
    @Index(name = "idx_trade_id", columnList = "tradeId", unique = true),
    @Index(name = "idx_tx_hash", columnList = "txHash", unique = true),
    @Index(name = "idx_buyer_id", columnList = "buyer_id"),
    @Index(name = "idx_seller_id", columnList = "seller_id"),
    @Index(name = "idx_trade_status", columnList = "status"),
    @Index(name = "idx_trade_offer", columnList = "offer_id"),
    @Index(name = "idx_trade_status_anchor", columnList = "status, anchor_checked_at")
})
public class TradeTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false, unique = true, length = 64)
    private String tradeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offer_id", foreignKey = @ForeignKey(name = "fk_trade_offer"))
    private EnergyOffer offer;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buyer_id", nullable = false, foreignKey = @ForeignKey(name = "fk_trade_buyer"))
    private User buyer;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false, foreignKey = @ForeignKey(name = "fk_trade_seller"))
    private User seller;

    @NotNull
    @Positive
    @Column(nullable = false)
    private Double quantityKwh;

    @NotNull
    @Positive
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal pricePerKwh;

    @NotNull
    @PositiveOrZero
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private TradeStatus status = TradeStatus.PENDING;

    @Column(length = 66)
    private String txHash;

    @Column
    private Long blockNumber;

    @Column(length = 255)
    private String rejectionReason;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime confirmedAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /** Buyer-supplied Idempotency-Key, unique per buyer. */
    @Column(length = 64)
    private String clientRequestId;

    // --- Blockchain submission tracking (write-ahead: stored before broadcasting) ---
    @Column
    private Long txNonce;

    @Column
    private LocalDateTime submittedAt;

    @Column(nullable = false)
    private int submissionAttempts = 0;

    @Column
    private Long chainId;

    @Column(length = 42)
    private String contractAddress;

    /** SHA-256 of the canonical trade record anchored on-chain. */
    @Column(length = 64)
    private String recordDigest;

    /** Deployment identity (contract deploymentId) of the current submission or anchor. */
    @Column(length = 66)
    private String deploymentId;

    /** Genesis block hash of the chain instance of the current submission or anchor. */
    @Column(length = 66)
    private String chainGenesisHash;

    /** Per-trade on-chain seller reference, persisted at reservation (survives key rotation). */
    @Column(length = 66)
    private String sellerRef;

    /** Per-trade on-chain buyer reference, persisted at reservation. */
    @Column(length = 66)
    private String buyerRef;

    /** Fingerprint of the pseudonym key that produced the references (never the key). */
    @Column(length = 40)
    private String privacyKeyId;

    /** Technical reason for NEEDS_REVIEW (administrators only; not shown to participants). */
    @Column(length = 255)
    private String reviewReason;

    /** Deployment an administrator approved for re-anchoring this trade. */
    @Column(length = 66)
    private String reanchorDeploymentId;

    /** Last time the anchor audit verified this CONFIRMED trade on-chain. */
    @Column
    private LocalDateTime anchorCheckedAt;

    public TradeTransaction() {
    }

    public TradeTransaction(String tradeId, EnergyOffer offer, User buyer, User seller,
                            Double quantityKwh, BigDecimal pricePerKwh, BigDecimal totalAmount,
                            TradeStatus status) {
        this.tradeId = tradeId;
        this.offer = offer;
        this.buyer = buyer;
        this.seller = seller;
        this.quantityKwh = quantityKwh;
        this.pricePerKwh = pricePerKwh;
        this.totalAmount = totalAmount;
        this.status = status != null ? status : TradeStatus.PENDING;
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

    // Getters and Setters
    public String getDeploymentId() {
        return deploymentId;
    }

    public void setDeploymentId(String deploymentId) {
        this.deploymentId = deploymentId;
    }

    public String getChainGenesisHash() {
        return chainGenesisHash;
    }

    public void setChainGenesisHash(String chainGenesisHash) {
        this.chainGenesisHash = chainGenesisHash;
    }

    public String getSellerRef() {
        return sellerRef;
    }

    public void setSellerRef(String sellerRef) {
        this.sellerRef = sellerRef;
    }

    public String getBuyerRef() {
        return buyerRef;
    }

    public void setBuyerRef(String buyerRef) {
        this.buyerRef = buyerRef;
    }

    public String getPrivacyKeyId() {
        return privacyKeyId;
    }

    public void setPrivacyKeyId(String privacyKeyId) {
        this.privacyKeyId = privacyKeyId;
    }

    public String getReviewReason() {
        return reviewReason;
    }

    public void setReviewReason(String reviewReason) {
        this.reviewReason = reviewReason;
    }

    public String getReanchorDeploymentId() {
        return reanchorDeploymentId;
    }

    public void setReanchorDeploymentId(String reanchorDeploymentId) {
        this.reanchorDeploymentId = reanchorDeploymentId;
    }

    public LocalDateTime getAnchorCheckedAt() {
        return anchorCheckedAt;
    }

    public void setAnchorCheckedAt(LocalDateTime anchorCheckedAt) {
        this.anchorCheckedAt = anchorCheckedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    public Long getTxNonce() {
        return txNonce;
    }

    public void setTxNonce(Long txNonce) {
        this.txNonce = txNonce;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public int getSubmissionAttempts() {
        return submissionAttempts;
    }

    public void setSubmissionAttempts(int submissionAttempts) {
        this.submissionAttempts = submissionAttempts;
    }

    public Long getChainId() {
        return chainId;
    }

    public void setChainId(Long chainId) {
        this.chainId = chainId;
    }

    public String getContractAddress() {
        return contractAddress;
    }

    public void setContractAddress(String contractAddress) {
        this.contractAddress = contractAddress;
    }

    public String getRecordDigest() {
        return recordDigest;
    }

    public void setRecordDigest(String recordDigest) {
        this.recordDigest = recordDigest;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTradeId() {
        return tradeId;
    }

    public void setTradeId(String tradeId) {
        this.tradeId = tradeId;
    }

    public EnergyOffer getOffer() {
        return offer;
    }

    public void setOffer(EnergyOffer offer) {
        this.offer = offer;
    }

    public User getBuyer() {
        return buyer;
    }

    public void setBuyer(User buyer) {
        this.buyer = buyer;
    }

    public User getSeller() {
        return seller;
    }

    public void setSeller(User seller) {
        this.seller = seller;
    }

    public Double getQuantityKwh() {
        return quantityKwh;
    }

    public void setQuantityKwh(Double quantityKwh) {
        this.quantityKwh = quantityKwh;
    }

    public BigDecimal getPricePerKwh() {
        return pricePerKwh;
    }

    public void setPricePerKwh(BigDecimal pricePerKwh) {
        this.pricePerKwh = pricePerKwh;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public TradeStatus getStatus() {
        return status;
    }

    public void setStatus(TradeStatus status) {
        this.status = status;
    }

    public String getTxHash() {
        return txHash;
    }

    public void setTxHash(String txHash) {
        this.txHash = txHash;
    }

    public Long getBlockNumber() {
        return blockNumber;
    }

    public void setBlockNumber(Long blockNumber) {
        this.blockNumber = blockNumber;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(LocalDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }
}
