package com.energytrading.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Periodic housekeeping (disable with {@code app.marketplace.scheduler-enabled=false}):
 * expires offers, releases timed-out reservations, retries PENDING submissions and reconciles
 * SUBMITTED trades. Each step is independent; a failure is logged and retried next cycle.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.marketplace.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class MarketplaceMaintenance {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceMaintenance.class);

    private final MarketplaceService marketplaceService;
    private final TradingService tradingService;

    public MarketplaceMaintenance(MarketplaceService marketplaceService, TradingService tradingService) {
        this.marketplaceService = marketplaceService;
        this.tradingService = tradingService;
    }

    /** Verifies that CONFIRMED trades are still anchored on the current deployment (alerts only; no reversal). */
    @Scheduled(initialDelayString = "${app.marketplace.maintenance-initial-delay-ms:15000}",
               fixedDelayString = "${app.marketplace.anchor-audit-interval-ms:300000}")
    public void anchorAudit() {
        try {
            tradingService.anchorAudit();
        } catch (RuntimeException e) {
            log.error("Anchor audit failed", e);
        }
    }

    @Scheduled(initialDelayString = "${app.marketplace.maintenance-initial-delay-ms:15000}",
               fixedDelayString = "${app.marketplace.maintenance-interval-ms:30000}")
    public void run() {
        try {
            marketplaceService.expireOffers();
        } catch (RuntimeException e) {
            log.error("Offer expiry failed", e);
        }
        try {
            tradingService.reconcileAll();
        } catch (RuntimeException e) {
            log.error("Blockchain reconciliation failed", e);
        }
        try {
            tradingService.expireStaleReservations();
        } catch (RuntimeException e) {
            log.error("Reservation expiry failed", e);
        }
    }
}
