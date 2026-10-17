package com.energytrading.service;

import com.energytrading.blockchain.BlockchainGateway.NetworkStatus;
import com.energytrading.dto.ResolutionRequest;
import com.energytrading.dto.TradeView;
import com.energytrading.exception.BusinessRuleException;
import com.energytrading.exception.ResourceNotFoundException;
import com.energytrading.model.TradeTransaction;
import com.energytrading.model.enums.TradeStatus;
import com.energytrading.repository.TradeTransactionRepository;
import com.energytrading.service.TradeSettlementService.Evidence;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Audited administrator recovery for trades that automation must not decide (NEEDS_REVIEW) and for
 * confirmed trades whose anchor was lost with a deployment. Every operation:
 * <ul>
 *   <li>is restricted to ADMIN by the controller and re-checks the trade state under its row lock,
 *       so a repeated or conflicting request fails with 409 instead of changing the outcome twice;</li>
 *   <li>requires a reason, recorded with the actor, prior and resulting state in the trade history
 *       and, with the evidence summary, in the audit log;</li>
 *   <li>gathers evidence from the verified current deployment and refuses when the blockchain cannot
 *       be verified (fail closed). An unverified trade is never confirmed to free a reservation.</li>
 * </ul>
 * There is deliberately no operation that reverses a CONFIRMED trade or moves money back.
 */
@Service
public class TradeRecoveryService {

    private final TradeTransactionRepository tradeRepository;
    private final TradeSettlementService settlementService;
    private final TradeStateService tradeStateService;
    private final BlockchainAlertService alertService;
    private final AuditService auditService;
    private final TradeViewMapper viewMapper;
    private final TransactionTemplate transactionTemplate;

    public TradeRecoveryService(TradeTransactionRepository tradeRepository, TradeSettlementService settlementService,
                                TradeStateService tradeStateService, BlockchainAlertService alertService,
                                AuditService auditService, TradeViewMapper viewMapper, PlatformTransactionManager transactionManager) {
        this.tradeRepository = tradeRepository;
        this.settlementService = settlementService;
        this.tradeStateService = tradeStateService;
        this.alertService = alertService;
        this.auditService = auditService;
        this.viewMapper = viewMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Releases a NEEDS_REVIEW trade as FAILED, only if the current deployment shows no possible success. */
    public TradeView resolveFailed(String adminCode, String tradeId, ResolutionRequest request) {
        TradeTransaction trade = requireStatus(tradeId, TradeStatus.NEEDS_REVIEW);
        Evidence ev = requireEvidence(tradeId);
        if (ev.recordedOnCurrent()) {
            throw BusinessRuleException.conflict("The trade is recorded on the current deployment; resolve it as confirmed instead");
        }
        if (ev.attemptPendingOnCurrent()) {
            throw BusinessRuleException.conflict("A submission attempt is still pending on the node; wait for its outcome");
        }
        if (ev.attemptsOnOtherDeployments() && !request.priorDeploymentRetired()) {
            throw BusinessRuleException.conflict("Earlier attempts were made on another deployment; set priorDeploymentRetired=true "
                    + "only after confirming that deployment can no longer record this trade");
        }
        Boolean done = transactionTemplate.execute(tx -> {
            TradeTransaction t = tradeRepository.lockByTradeId(tradeId).orElseThrow();
            if (t.getStatus() != TradeStatus.NEEDS_REVIEW) {
                return false;
            }
            t.setReviewReason(truncate("resolved as failed by " + adminCode + ": " + request.reason()));
            tradeStateService.release(t, TradeStatus.FAILED, adminCode, "Resolved as failed by an administrator");
            return true;
        });
        if (!Boolean.TRUE.equals(done)) {
            throw BusinessRuleException.conflict("The trade was resolved concurrently");
        }
        if (request.priorDeploymentRetired()) {
            settlementService.retireAttemptsOnOtherDeployments(tradeId, ev.currentDeploymentId(), adminCode);
        }
        alertService.resolveForTrade(tradeId, adminCode, "resolved as failed");
        auditService.record(adminCode, "TRADE_RESOLVE_FAILED", "SUCCESS", "tradeId=" + tradeId + " reason=" + request.reason()
                + " evidence=[notRecordedOn=" + ev.currentDeploymentId() + ", priorDeploymentRetired=" + request.priorDeploymentRetired() + "]");
        return view(tradeId);
    }

    /** Confirms a NEEDS_REVIEW trade only with full evidence on the current deployment (seller paid). */
    public TradeView resolveConfirmed(String adminCode, String tradeId, ResolutionRequest request) {
        requireStatus(tradeId, TradeStatus.NEEDS_REVIEW);
        Evidence ev = requireEvidence(tradeId);
        if (!ev.confirmsRecord()) {
            throw BusinessRuleException.conflict("The evidence does not establish the trade on the current deployment: "
                    + String.join("; ", ev.problems()));
        }
        if (settlementService.confirmFromEvidence(tradeId, adminCode, request.reason()) != TradeSettlementService.Outcome.CONFIRMED) {
            throw BusinessRuleException.conflict("The trade was resolved concurrently");
        }
        alertService.resolveForTrade(tradeId, adminCode, "resolved as confirmed");
        auditService.record(adminCode, "TRADE_RESOLVE_CONFIRMED", "SUCCESS", "tradeId=" + tradeId + " reason=" + request.reason()
                + " evidence=[deployment=" + ev.currentDeploymentId() + ", tx=" + ev.eventTxHash() + ", match=" + ev.match() + "]");
        return view(tradeId);
    }

    /**
     * Re-anchors a trade on the current deployment after an operator reviewed a chain reset or
     * redeployment: a NEEDS_REVIEW trade is resubmitted there (and confirmed only on its receipt
     * evidence); a CONFIRMED trade whose deployment changed gets a new anchor without moving money.
     */
    public TradeView reanchor(String adminCode, String tradeId, ResolutionRequest request) {
        TradeTransaction trade = tradeRepository.findByTradeId(tradeId).orElseThrow(() -> new ResourceNotFoundException("Trade not found"));
        NetworkStatus net = settlementService.currentNetwork()
                .orElseThrow(() -> BusinessRuleException.conflict("The blockchain cannot be verified; re-anchoring is not possible now"));
        Evidence ev = requireEvidence(tradeId);
        if (ev.recordedOnCurrent()) {
            throw BusinessRuleException.conflict("The trade is already recorded on the current deployment; "
                    + (trade.getStatus() == TradeStatus.CONFIRMED ? "the anchor audit will verify it" : "resolve it as confirmed instead"));
        }
        if (!request.priorDeploymentRetired()) {
            throw BusinessRuleException.conflict("Re-anchoring requires priorDeploymentRetired=true after reviewing the earlier deployment");
        }
        TradeStatus before = trade.getStatus();
        if (before == TradeStatus.NEEDS_REVIEW) {
            Boolean done = transactionTemplate.execute(tx -> {
                TradeTransaction t = tradeRepository.lockByTradeId(tradeId).orElseThrow();
                if (t.getStatus() != TradeStatus.NEEDS_REVIEW) {
                    return false;
                }
                t.setReanchorDeploymentId(net.deploymentId());
                t.setTxHash(null);
                t.setTxNonce(null);
                t.setRejectionReason(null);
                t.setReviewReason(truncate("re-anchor approved by " + adminCode + ": " + request.reason()));
                tradeStateService.transition(t, TradeStatus.PENDING, adminCode, "re-anchor to the current deployment approved");
                return true;
            });
            if (!Boolean.TRUE.equals(done)) {
                throw BusinessRuleException.conflict("The trade was resolved concurrently");
            }
            settlementService.retireAttemptsOnOtherDeployments(tradeId, net.deploymentId(), adminCode);
            alertService.resolveForTrade(tradeId, adminCode, "re-anchor approved");
            audit(adminCode, tradeId, request, net, before);
            settlementService.submit(tradeId);
            return view(tradeId);
        }
        if (before == TradeStatus.CONFIRMED) {
            if (trade.getDeploymentId() != null && trade.getDeploymentId().equalsIgnoreCase(net.deploymentId())) {
                throw BusinessRuleException.conflict("The trade is anchored on the current deployment; nothing to re-anchor");
            }
            Boolean approved = transactionTemplate.execute(tx -> {
                TradeTransaction t = tradeRepository.lockByTradeId(tradeId).orElseThrow();
                if (t.getStatus() != TradeStatus.CONFIRMED || net.deploymentId().equalsIgnoreCase(t.getReanchorDeploymentId())) {
                    return false;
                }
                t.setReanchorDeploymentId(net.deploymentId());
                tradeStateService.transition(t, TradeStatus.CONFIRMED, adminCode, "re-anchor to the current deployment approved");
                return true;
            });
            if (!Boolean.TRUE.equals(approved)) {
                throw BusinessRuleException.conflict("A re-anchor to the current deployment is already approved; the anchor audit retries it");
            }
            audit(adminCode, tradeId, request, net, before);
            settlementService.submitReanchor(tradeId);
            return view(tradeId);
        }
        throw BusinessRuleException.conflict("Only NEEDS_REVIEW or CONFIRMED trades can be re-anchored (status " + before + ")");
    }

    private void audit(String adminCode, String tradeId, ResolutionRequest request, NetworkStatus net, TradeStatus before) {
        auditService.record(adminCode, "TRADE_REANCHOR", "APPROVED", "tradeId=" + tradeId + " from=" + before
                + " deployment=" + net.deploymentId() + " reason=" + request.reason());
    }

    private TradeTransaction requireStatus(String tradeId, TradeStatus status) {
        TradeTransaction t = tradeRepository.findByTradeId(tradeId).orElseThrow(() -> new ResourceNotFoundException("Trade not found"));
        if (t.getStatus() != status) {
            throw BusinessRuleException.conflict("Only " + status + " trades can be resolved this way (status " + t.getStatus() + ")");
        }
        return t;
    }

    private Evidence requireEvidence(String tradeId) {
        return settlementService.evidenceFor(tradeId).orElseThrow(() -> BusinessRuleException.conflict(
                "The blockchain cannot be verified, so no evidence can be gathered; resolve the network problem first"));
    }

    private TradeView view(String tradeId) {
        return transactionTemplate.execute(tx -> viewMapper.toTradeView(tradeRepository.findByTradeId(tradeId).orElseThrow(), null, true));
    }

    private static String truncate(String text) {
        return text.length() > 255 ? text.substring(0, 255) : text;
    }
}
