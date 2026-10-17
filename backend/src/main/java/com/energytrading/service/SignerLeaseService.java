package com.energytrading.service;

import com.energytrading.config.BlockchainProperties;
import com.energytrading.model.SignerLease;
import com.energytrading.repository.SignerLeaseRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Cross-instance lease on the blockchain signer, coordinated through MySQL.
 * <p>
 * Only the lease holder may sign and broadcast. Acquisition, renewal and takeover each run in a
 * short transaction that locks the lease row; expiry is judged by the <b>database clock</b>, so
 * instance clock skew does not matter. A takeover (after the previous holder's lease expired, e.g.
 * because it crashed) increments the fencing token. Every write-ahead step re-validates holder,
 * token and remaining lifetime inside the same transaction that records the submission, so a stale
 * holder can never record a new submission; a holder whose lease runs low abandons the signed
 * transaction before broadcasting it. No database transaction is held open during RPC waits.
 * <p>
 * Residual risk: a holder that passes the pre-broadcast check and is then paused for longer than
 * the remaining lifetime could still broadcast late. Any such conflict is resolved by Ethereum
 * nonce semantics (at most one transaction per nonce is mined) and the contract's duplicate-key
 * rule, and is recorded as a submission attempt.
 */
@Service
public class SignerLeaseService {

    private static final Logger log = LoggerFactory.getLogger(SignerLeaseService.class);

    public record Lease(String signerAddress, String holderId, long token, LocalDateTime expiresAt) {
    }

    private final SignerLeaseRepository repository;
    private final BlockchainProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final String instanceId;
    private volatile String heldSigner;

    @Autowired
    public SignerLeaseService(SignerLeaseRepository repository, BlockchainProperties properties,
                              PlatformTransactionManager transactionManager) {
        this(repository, properties, transactionManager, "instance-" + UUID.randomUUID());
    }

    /** Explicit instance ID (used by tests to simulate several backend instances). */
    public SignerLeaseService(SignerLeaseRepository repository, BlockchainProperties properties,
                              PlatformTransactionManager transactionManager, String instanceId) {
        this.repository = repository;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.instanceId = instanceId;
    }

    public String instanceId() {
        return instanceId;
    }

    /** Acquires, renews or takes over (if expired) the lease; empty if another live holder owns it. */
    public Optional<Lease> acquire(String signerAddress) {
        String signer = signerAddress.toLowerCase();
        try {
            return transactionTemplate.execute(tx -> acquireInTransaction(signer));
        } catch (DataIntegrityViolationException e) {
            // Another instance inserted the first lease row concurrently: retry once against it.
            return transactionTemplate.execute(tx -> acquireInTransaction(signer));
        }
    }

    private Optional<Lease> acquireInTransaction(String signer) {
        LocalDateTime now = databaseNow();
        LocalDateTime expiry = now.plus(properties.signerLeaseTtl());
        Optional<SignerLease> existing = repository.lock(signer);
        SignerLease lease;
        if (existing.isEmpty()) {
            lease = repository.saveAndFlush(new SignerLease(signer, instanceId, 1, now, expiry));
            log.info("Signer lease acquired by {} (token {})", instanceId, lease.getFencingToken());
        } else {
            lease = existing.get();
            boolean mine = instanceId.equals(lease.getHolderId());
            boolean live = lease.getExpiresAt().isAfter(now);
            if (mine && live) {
                lease.renew(now, expiry);
            } else if (!live) {
                String previous = lease.getHolderId();
                lease.takeOver(instanceId, now, expiry);
                log.warn("Signer lease taken over by {} from {} after expiry (token {})", instanceId, previous, lease.getFencingToken());
            } else {
                return Optional.empty();
            }
            repository.save(lease);
        }
        heldSigner = signer;
        return Optional.of(new Lease(signer, lease.getHolderId(), lease.getFencingToken(), lease.getExpiresAt()));
    }

    /**
     * Must run inside the caller's transaction (the write-ahead transaction): true only if this
     * instance still holds the lease with the same fencing token and at least {@code margin} left.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean validate(Lease lease, Duration margin) {
        LocalDateTime now = databaseNow();
        return repository.lock(lease.signerAddress())
                .filter(l -> instanceId.equals(l.getHolderId()))
                .filter(l -> l.getFencingToken() == lease.token())
                .filter(l -> l.getExpiresAt().isAfter(now.plus(margin)))
                .isPresent();
    }

    /** Stand-alone validity check (own short transaction). */
    public boolean stillValid(Lease lease, Duration margin) {
        return Boolean.TRUE.equals(transactionTemplate.execute(tx -> validate(lease, margin)));
    }

    /** Releases the lease held by this instance (shutdown). */
    @PreDestroy
    public void releaseHeld() {
        String signer = heldSigner;
        if (signer == null) {
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(tx -> repository.lock(signer)
                    .filter(l -> instanceId.equals(l.getHolderId()))
                    .ifPresent(l -> {
                        l.expireNow(databaseNow());
                        repository.save(l);
                    }));
        } catch (RuntimeException e) {
            log.warn("Could not release signer lease on shutdown; it expires after {}", properties.signerLeaseTtl());
        }
    }

    LocalDateTime databaseNow() {
        return repository.databaseNow().toLocalDateTime();
    }
}
