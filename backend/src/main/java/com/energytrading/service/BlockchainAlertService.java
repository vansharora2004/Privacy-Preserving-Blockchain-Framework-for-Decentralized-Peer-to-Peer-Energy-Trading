package com.energytrading.service;

import com.energytrading.dto.BlockchainAlertView;
import com.energytrading.dto.PageResponse;
import com.energytrading.model.BlockchainAlert;
import com.energytrading.repository.BlockchainAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * De-duplicated blockchain alerts. A repeated condition increments the occurrence count and the
 * last-seen time of the open alert instead of writing a new audit entry every reconciliation cycle;
 * the audit log is written when an alert opens and when it is resolved.
 * Alert details must not contain household codes, names, credentials or keys.
 */
@Service
public class BlockchainAlertService {

    public static final String NETWORK = "NETWORK_VERIFICATION_FAILED";
    public static final String LEASE = "SIGNER_LEASE_UNAVAILABLE";
    public static final String NEEDS_REVIEW = "TRADE_NEEDS_REVIEW";
    public static final String ANCHOR_MISSING = "ANCHOR_MISSING";
    public static final String ANCHOR_INVALID = "ANCHOR_INVALID";

    private static final Logger log = LoggerFactory.getLogger(BlockchainAlertService.class);

    private final BlockchainAlertRepository repository;
    private final AuditService auditService;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public BlockchainAlertService(BlockchainAlertRepository repository, AuditService auditService, Clock clock,
                                  PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.auditService = auditService;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Opens an alert or counts a recurrence of the open one.
     *
     * @return true if the alert was newly opened (and audited)
     */
    public boolean raise(String type, String openKey, String tradeRef, String detail, String auditAction) {
        Boolean opened;
        try {
            opened = transactionTemplate.execute(tx -> upsert(type, openKey, tradeRef, detail));
        } catch (DataIntegrityViolationException e) {
            opened = transactionTemplate.execute(tx -> upsert(type, openKey, tradeRef, detail)); // lost an insert race
        }
        if (Boolean.TRUE.equals(opened)) {
            auditService.record("SYSTEM", auditAction, "FAILED", detail);
            log.warn("Blockchain alert opened [{}]: {}", type, detail);
            return true;
        }
        return false;
    }

    private Boolean upsert(String type, String openKey, String tradeRef, String detail) {
        LocalDateTime now = LocalDateTime.now(clock);
        var existing = repository.lockOpen(openKey);
        if (existing.isPresent()) {
            existing.get().recur(detail, now);
            repository.save(existing.get());
            return false;
        }
        repository.saveAndFlush(new BlockchainAlert(type, openKey, tradeRef, detail, now));
        return true;
    }

    /** Resolves the open alert with this key, if any. */
    public void resolve(String openKey, String by, String resolution) {
        Boolean resolved = transactionTemplate.execute(tx -> repository.lockOpen(openKey).map(a -> {
            a.resolve(by, resolution, LocalDateTime.now(clock));
            repository.save(a);
            return true;
        }).orElse(false));
        if (Boolean.TRUE.equals(resolved)) {
            auditService.record(by, "BLOCKCHAIN_ALERT_RESOLVED", "SUCCESS", "alert=" + openKey + " " + resolution);
        }
    }

    public void resolveAllOfType(String type, String by, String resolution) {
        for (BlockchainAlert alert : repository.findByAlertTypeAndResolvedAtIsNull(type)) {
            resolve(alert.getOpenKey(), by, resolution);
        }
    }

    public void resolveForTrade(String tradeRef, String by, String resolution) {
        for (BlockchainAlert alert : repository.findByTradeRefAndResolvedAtIsNull(tradeRef)) {
            resolve(alert.getOpenKey(), by, resolution);
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<BlockchainAlertView> open(int page, int size) {
        PageRequest request = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Order.desc("lastSeenAt"), Sort.Order.desc("id")));
        return PageResponse.from(repository.findByResolvedAtIsNull(request), a -> new BlockchainAlertView(a.getId(),
                a.getAlertType(), a.getTradeRef(), a.getDetail(), a.getOccurrences(), a.getFirstSeenAt(), a.getLastSeenAt()));
    }
}
