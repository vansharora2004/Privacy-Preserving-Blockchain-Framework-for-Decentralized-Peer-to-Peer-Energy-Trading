package com.energytrading.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Cross-instance lease on one blockchain signer. The holder may sign and broadcast; every takeover
 * increments {@code fencingToken}, and every write-ahead step re-validates holder, token and
 * expiry inside the same database transaction, so a stale holder cannot record new submissions.
 */
@Entity
@Table(name = "blockchain_signer_lease")
public class SignerLease {

    @Id
    @Column(length = 42)
    private String signerAddress;

    @Column(nullable = false, length = 64)
    private String holderId;

    @Column(nullable = false)
    private long fencingToken;

    @Column(nullable = false)
    private LocalDateTime acquiredAt;

    @Column(nullable = false)
    private LocalDateTime renewedAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    protected SignerLease() {
    }

    public SignerLease(String signerAddress, String holderId, long fencingToken, LocalDateTime now, LocalDateTime expiresAt) {
        this.signerAddress = signerAddress;
        this.holderId = holderId;
        this.fencingToken = fencingToken;
        this.acquiredAt = now;
        this.renewedAt = now;
        this.expiresAt = expiresAt;
    }

    public void takeOver(String holderId, LocalDateTime now, LocalDateTime expiresAt) {
        this.holderId = holderId;
        this.fencingToken++;
        this.acquiredAt = now;
        this.renewedAt = now;
        this.expiresAt = expiresAt;
    }

    public void renew(LocalDateTime now, LocalDateTime expiresAt) {
        this.renewedAt = now;
        this.expiresAt = expiresAt;
    }

    public void expireNow(LocalDateTime now) {
        this.expiresAt = now;
    }

    public String getSignerAddress() { return signerAddress; }
    public String getHolderId() { return holderId; }
    public long getFencingToken() { return fencingToken; }
    public LocalDateTime getAcquiredAt() { return acquiredAt; }
    public LocalDateTime getRenewedAt() { return renewedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
}
