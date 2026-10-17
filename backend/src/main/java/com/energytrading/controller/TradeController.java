package com.energytrading.controller;

import com.energytrading.dto.BlockchainAlertView;
import com.energytrading.dto.BlockchainStatusView;
import com.energytrading.dto.ResolutionRequest;
import com.energytrading.dto.SubmissionAttemptView;
import com.energytrading.dto.PageResponse;
import com.energytrading.dto.TradeRequest;
import com.energytrading.dto.TradeResult;
import com.energytrading.dto.TradeVerificationView;
import com.energytrading.dto.TradeView;
import com.energytrading.service.BlockchainAlertService;
import com.energytrading.service.TradeRecoveryService;
import com.energytrading.service.TradeSettlementService;
import com.energytrading.service.TradingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TradeController {

    private final TradingService tradingService;
    private final TradeSettlementService settlementService;
    private final TradeRecoveryService recoveryService;
    private final BlockchainAlertService alertService;

    public TradeController(TradingService tradingService, TradeSettlementService settlementService,
                           TradeRecoveryService recoveryService, BlockchainAlertService alertService) {
        this.tradingService = tradingService;
        this.settlementService = settlementService;
        this.recoveryService = recoveryService;
        this.alertService = alertService;
    }

    /**
     * Requests a purchase. The Idempotency-Key header is required; retrying with the same key
     * returns the original trade (200). 201 CONFIRMED, 202 PENDING/FLAGGED/SUBMITTED,
     * 200 FAILED (trade created but not recorded), 422 REJECTED.
     */
    @PostMapping(value = "/api/trades", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('HOUSEHOLD')")
    public ResponseEntity<TradeResult> request(@Valid @RequestBody TradeRequest request,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                               Authentication authentication) {
        TradeResult result = tradingService.requestTrade(authentication.getName(), request, idempotencyKey);
        HttpStatus status;
        if (result.replayed()) {
            status = HttpStatus.OK;
        } else {
            status = switch (result.outcome()) {
                case "CONFIRMED" -> HttpStatus.CREATED;
                case "PENDING", "FLAGGED", "SUBMITTED" -> HttpStatus.ACCEPTED;
                case "REJECTED" -> HttpStatus.UNPROCESSABLE_ENTITY;
                default -> HttpStatus.OK;
            };
        }
        return ResponseEntity.status(status).body(result);
    }

    @GetMapping("/api/trades/mine")
    @PreAuthorize("hasRole('HOUSEHOLD')")
    public PageResponse<TradeView> mine(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                                        Authentication authentication) {
        return tradingService.tradesOf(authentication.getName(), page, size);
    }

    @GetMapping("/api/trades")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUDITOR')")
    public PageResponse<TradeView> all(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return tradingService.allTrades(page, size);
    }

    /** Buyer, seller, administrator or auditor. */
    @GetMapping("/api/trades/{tradeId}")
    @PreAuthorize("hasAnyRole('HOUSEHOLD', 'ADMIN', 'AUDITOR')")
    public TradeView get(@PathVariable String tradeId, Authentication authentication) {
        return tradingService.get(authentication, tradeId);
    }

    @GetMapping("/api/trades/{tradeId}/verification")
    @PreAuthorize("hasAnyRole('HOUSEHOLD', 'ADMIN', 'AUDITOR')")
    public TradeVerificationView verify(@PathVariable String tradeId, Authentication authentication) {
        return tradingService.verify(authentication, tradeId);
    }

    @PostMapping("/api/trades/{tradeId}/cancel")
    @PreAuthorize("hasAnyRole('HOUSEHOLD', 'ADMIN')")
    public TradeView cancel(@PathVariable String tradeId, Authentication authentication) {
        return tradingService.cancel(authentication, tradeId);
    }

    @PostMapping("/api/trades/{tradeId}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public TradeView approve(@PathVariable String tradeId, Authentication authentication) {
        return tradingService.approve(authentication.getName(), tradeId);
    }

    @PostMapping("/api/trades/{tradeId}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public TradeView reject(@PathVariable String tradeId, Authentication authentication) {
        return tradingService.reject(authentication.getName(), tradeId);
    }

    @PostMapping("/api/trades/{tradeId}/reconcile")
    @PreAuthorize("hasRole('ADMIN')")
    public TradeView reconcile(@PathVariable String tradeId, Authentication authentication) {
        return tradingService.reconcile(authentication.getName(), tradeId);
    }

    /** Releases a NEEDS_REVIEW trade as FAILED when the evidence supports it (reason required, audited). */
    @PostMapping(value = "/api/trades/{tradeId}/resolve-failed", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public TradeView resolveFailed(@PathVariable String tradeId, @Valid @RequestBody ResolutionRequest request,
                                   Authentication authentication) {
        return recoveryService.resolveFailed(authentication.getName(), tradeId, request);
    }

    /** Confirms a NEEDS_REVIEW trade only with verified on-chain evidence (reason required, audited). */
    @PostMapping(value = "/api/trades/{tradeId}/resolve-confirmed", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public TradeView resolveConfirmed(@PathVariable String tradeId, @Valid @RequestBody ResolutionRequest request,
                                      Authentication authentication) {
        return recoveryService.resolveConfirmed(authentication.getName(), tradeId, request);
    }

    /** Re-anchors a trade on the current deployment after a reviewed chain reset or redeployment. */
    @PostMapping(value = "/api/trades/{tradeId}/re-anchor", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public TradeView reanchor(@PathVariable String tradeId, @Valid @RequestBody ResolutionRequest request,
                              Authentication authentication) {
        return recoveryService.reanchor(authentication.getName(), tradeId, request);
    }

    /** Every signed transaction of a trade, including superseded attempts (technical detail). */
    @GetMapping("/api/trades/{tradeId}/attempts")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUDITOR')")
    public List<SubmissionAttemptView> attempts(@PathVariable String tradeId) {
        tradingService.requireExists(tradeId);
        return settlementService.attempts(tradeId);
    }

    /** Open, de-duplicated blockchain alerts (network, lease, NEEDS_REVIEW, missing or invalid anchors). */
    @GetMapping("/api/blockchain/alerts")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUDITOR')")
    public PageResponse<BlockchainAlertView> alerts(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return alertService.open(page, size);
    }

    /** Runs the anchor audit now (operator procedure after a chain reset); returns the number checked. */
    @PostMapping("/api/blockchain/anchor-audit")
    @PreAuthorize("hasRole('ADMIN')")
    public java.util.Map<String, Integer> anchorAudit(Authentication authentication) {
        return java.util.Map.of("checked", tradingService.anchorAudit());
    }

    /** Network and contract verification (never includes the private key). */
    @GetMapping("/api/blockchain/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUDITOR')")
    public BlockchainStatusView blockchainStatus() {
        return settlementService.status();
    }
}
