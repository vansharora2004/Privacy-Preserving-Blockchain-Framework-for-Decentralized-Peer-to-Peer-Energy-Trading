package com.energytrading.service;

import com.energytrading.blockchain.FakeBlockchainGateway.Mode;
import com.energytrading.blockchain.FakeBlockchainGateway.ReceiptStyle;
import com.energytrading.dto.ResolutionRequest;
import com.energytrading.dto.SubmissionAttemptView;
import com.energytrading.dto.TradeResult;
import com.energytrading.exception.BusinessRuleException;
import com.energytrading.model.TradeTransaction;
import com.energytrading.model.enums.Role;
import com.energytrading.model.enums.TradeStatus;
import com.energytrading.service.TradeSettlementService.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Remediation tests: deployment identity, NEEDS_REVIEW recovery, evidence, history and resilience. */
class BlockchainRecoveryTests extends FakeChainTestSupport {

    private static final ResolutionRequest REASON = new ResolutionRequest("Reviewed after the local chain was reset", true);
    private static final ResolutionRequest NO_ATTESTATION = new ResolutionRequest("Reviewed after the local chain was reset", false);

    // ================================================================== Phase A: deployment identity

    @Nested
    class DeploymentIdentity {

        @Test
        @DisplayName("A4: reset + redeploy at the SAME address: refused until reviewed, then NEEDS_REVIEW; never resubmitted")
        void resetAtSameAddress() {
            String tradeId = submittedTrade("2");
            assertThat(chain.broadcasts.get()).isEqualTo(1);
            String oldDeployment = trade(tradeId).getDeploymentId();

            chain.resetChainAndRedeploySameAddress();
            assertThat(settlement.reconcile(tradeId)).as("unreviewed deployment is refused").isEqualTo(Outcome.UNAVAILABLE);
            assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.UNAVAILABLE);
            assertThat(alertRepository.findByOpenKey("NETWORK:DEPLOYMENT_ID_MISMATCH")).get()
                    .satisfies(a -> assertThat(a.getOccurrences()).isGreaterThanOrEqualTo(2));
            assertThat(trade(tradeId).getStatus()).isEqualTo(TradeStatus.SUBMITTED);

            chain.acceptCurrentDeploymentInConfiguration();
            ageSubmission(tradeId);
            assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.NEEDS_REVIEW);
            TradeTransaction t = trade(tradeId);
            assertThat(t.getStatus()).isEqualTo(TradeStatus.NEEDS_REVIEW);
            assertThat(t.getReviewReason()).contains("deployment changed").contains(oldDeployment);
            assertThat(t.getTxHash()).as("evidence of the earlier transaction is kept").isNotNull();
            assertThat(chain.broadcasts.get()).as("not resubmitted to the new deployment").isEqualTo(1);
            assertThat(balance(buyer)).as("escrow untouched").isEqualByComparingTo("90.00");
            assertThat(remaining()).isEqualTo(3.0);
            assertThat(openAlerts("TRADE_NEEDS_REVIEW", tradeId)).isEqualTo(1);
        }

        @Test
        @DisplayName("A4: redeployment at a DIFFERENT address: the submitted trade needs review, nothing is resubmitted")
        void redeployAtNewAddress() {
            String tradeId = submittedTrade("2");
            chain.redeployAtNewAddress();
            chain.acceptCurrentDeploymentInConfiguration();

            assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.NEEDS_REVIEW);
            assertThat(chain.broadcasts.get()).isEqualTo(1);
            assertThat(chain.recordedTradeCount()).isZero();
        }

        @Test
        @DisplayName("A4: chain identity mismatch (wrong chain ID or unreviewed deployment) blocks every submission")
        void identityMismatchBlocksSubmission() {
            chain.redeployAtNewAddress(); // configuration still names the old deployment
            TradeResult result = buy("1");
            assertThat(result.outcome()).isEqualTo("PENDING");
            assertThat(settlement.submit(result.trade().tradeId())).isEqualTo(Outcome.UNAVAILABLE);
            assertThat(chain.broadcasts.get()).isZero();
            assertThat(settlement.status().problem()).contains("deployment ID mismatch");

            chain.acceptCurrentDeploymentInConfiguration();
            assertThat(settlement.submit(result.trade().tradeId())).as("a never-submitted trade may go to the reviewed deployment")
                    .isEqualTo(Outcome.CONFIRMED);
        }

        @Test
        @DisplayName("A4: mined before the RPC response was lost, then the chain was reset: NEEDS_REVIEW, not resubmitted")
        void minedThenResponseLostThenReset() {
            chain.mode = Mode.UNCERTAIN_MINED;
            chain.receiptFailures.set(1000);
            String tradeId = buy("2").trade().tradeId();
            assertThat(trade(tradeId).getStatus()).isEqualTo(TradeStatus.SUBMITTED);
            chain.receiptFailures.set(0);
            chain.mode = Mode.SUCCESS;

            resetChainAndAcceptNewDeployment();
            assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.NEEDS_REVIEW);
            assertThat(chain.broadcasts.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("A4: a PENDING trade whose earlier attempt was on another deployment is not resubmitted automatically")
        void pendingWithAttemptOnOldDeployment() {
            chain.mode = Mode.UNCERTAIN_LOST;
            String tradeId = buy("2").trade().tradeId();
            ageSubmission(tradeId);
            assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.RETRY); // same deployment, nonce unused
            assertThat(trade(tradeId).getStatus()).isEqualTo(TradeStatus.PENDING);

            chain.mode = Mode.SUCCESS;
            resetChainAndAcceptNewDeployment();
            assertThat(settlement.submit(tradeId)).isEqualTo(Outcome.NEEDS_REVIEW);
            assertThat(chain.broadcasts.get()).isEqualTo(1);
            assertThat(trade(tradeId).getReviewReason()).contains("different deployment");
        }

        @Test
        @DisplayName("A4: never-accepted transaction on the same deployment: retried with the same nonce while unused")
        void neverAcceptedSameNonce() {
            chain.mode = Mode.UNCERTAIN_LOST;
            String tradeId = buy("2").trade().tradeId();
            long nonce = trade(tradeId).getTxNonce();
            ageSubmission(tradeId);
            chain.mode = Mode.SUCCESS;

            assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.RETRY);
            assertThat(trade(tradeId).getTxNonce()).as("nonce pinned for the retry").isEqualTo(nonce);
            assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.CONFIRMED);
            assertThat(trade(tradeId).getTxNonce()).isEqualTo(nonce);
            assertThat(chain.recordedTradeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("A4/G1: nonce consumed by another transaction: retried with a fresh nonce; both attempts are preserved")
        void consumedNonceFreshRetry() {
            chain.mode = Mode.UNCERTAIN_LOST;
            String lost = buy("2").trade().tradeId();
            chain.mode = Mode.SUCCESS;
            String other = buy("1").trade().tradeId(); // takes the same (still unused) nonce and is mined
            assertThat(trade(other).getStatus()).isEqualTo(TradeStatus.CONFIRMED);
            ageSubmission(lost);

            assertThat(settlement.reconcile(lost)).isEqualTo(Outcome.RETRY);
            assertThat(trade(lost).getTxNonce()).as("fresh nonce on retry").isNull();
            assertThat(settlement.reconcile(lost)).isEqualTo(Outcome.CONFIRMED);

            List<SubmissionAttemptView> attempts = settlement.attempts(lost);
            assertThat(attempts).hasSize(2);
            assertThat(attempts).extracting(SubmissionAttemptView::status).containsExactly("DROPPED", "MINED_SUCCESS");
            assertThat(attempts.get(0).detail()).contains("consumed by another transaction");
            assertThat(attempts).extracting(SubmissionAttemptView::txHash).doesNotHaveDuplicates();
            assertThat(attempts).allSatisfy(a -> assertThat(a.deploymentId()).isEqualTo(chain.currentDeploymentId()));
        }
    }

    // ================================================================== Phase A3: anchor audit

    @Nested
    class AnchorAudit {

        @Test
        @DisplayName("A3/A4: confirmed trades missing after a reset raise one ANCHOR_MISSING alert; nothing is reversed")
        void confirmedMissingAfterReset() throws Exception {
            String tradeId = confirmedTrade("2");
            assertThat(balance(seller)).isEqualByComparingTo("10.00");
            assertThat(settlement.anchorAudit()).isGreaterThanOrEqualTo(1);
            assertThat(openAlerts("ANCHOR_MISSING", tradeId)).isZero();

            resetChainAndAcceptNewDeployment();
            long auditsBefore = audits("BLOCKCHAIN_ANCHOR");
            settlement.anchorAudit();
            settlement.anchorAudit();

            assertThat(openAlerts("ANCHOR_MISSING", tradeId)).isEqualTo(1);
            assertThat(alertRepository.findByOpenKey("ANCHOR:" + tradeId)).get()
                    .satisfies(a -> assertThat(a.getOccurrences()).isGreaterThanOrEqualTo(2));
            assertThat(audits("BLOCKCHAIN_ANCHOR") - auditsBefore).as("audited once, not per run").isEqualTo(1);
            assertThat(trade(tradeId).getStatus()).isEqualTo(TradeStatus.CONFIRMED);
            assertThat(balance(seller)).as("no automatic reversal").isEqualByComparingTo("10.00");
            assertThat(settlement.verifyOnChain(tradeId)).isEqualTo("DEPLOYMENT_CHANGED");
        }

        @Test
        @DisplayName("D/A3: a confirmed trade voided on-chain is flagged ANCHOR_INVALID and verifies as VOIDED; Settled stays valid")
        void voidedAndSettledAnchors() throws Exception {
            String voided = confirmedTrade("1");
            String settled = confirmedTrade("1");
            chain.voidOnChain(payload(voided).tradeKey());
            chain.settleOnChain(payload(settled).tradeKey());

            settlement.anchorAudit();
            assertThat(openAlerts("ANCHOR_INVALID", voided)).isEqualTo(1);
            assertThat(openAlerts("ANCHOR_INVALID", settled)).isZero();
            assertThat(settlement.verifyOnChain(voided)).isEqualTo("VOIDED");
            assertThat(settlement.verifyOnChain(settled)).isEqualTo("MATCH");
            assertThat(trade(voided).getStatus()).isEqualTo(TradeStatus.CONFIRMED);
        }
    }

    // ================================================================== Phase D: evidence

    @Nested
    class Evidence {

        @Test
        @DisplayName("D: a Voided on-chain record is never adopted as a successful trade")
        void voidedNotAdopted() {
            String tradeId = pendingTrade("2");
            chain.recordOutOfBand(payload(tradeId));
            chain.voidOnChain(payload(tradeId).tradeKey());
            assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.NEEDS_REVIEW);
            assertThat(trade(tradeId).getReviewReason()).contains("VOIDED");
            assertThat(balance(seller)).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("D: a Settled on-chain record with full evidence is adopted")
        void settledAdopted() {
            String tradeId = pendingTrade("2");
            chain.recordOutOfBand(payload(tradeId));
            chain.settleOnChain(payload(tradeId).tradeKey());
            assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.CONFIRMED);
        }

        @Test
        @DisplayName("D/M2: a recorded trade without a TradeRecorded event is not confirmed (no hash-less confirmation)")
        void missingEvent() {
            String tradeId = pendingTrade("2");
            chain.recordOutOfBand(payload(tradeId));
            chain.removeEventLog(payload(tradeId).tradeKey());
            assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.NEEDS_REVIEW);
            assertThat(trade(tradeId).getTxHash()).isNull();
            assertThat(trade(tradeId).getReviewReason()).contains("no TradeRecorded event");
        }

        @Test
        @DisplayName("D/L3: a successful receipt from the wrong contract is not a confirmation")
        void wrongContractReceipt() {
            chain.receiptStyle = ReceiptStyle.WRONG_TARGET;
            TradeResult result = buy("2");
            assertThat(result.outcome()).isEqualTo("NEEDS_REVIEW");
            assertThat(trade(result.trade().tradeId()).getReviewReason()).contains("does not target the configured contract");
            assertThat(balance(seller)).isEqualByComparingTo("0.00");
            assertThat(result.trade().statusDetail()).isEqualTo("Held for administrator review");
        }

        @Test
        @DisplayName("D/L3: a successful receipt whose event is for another trade (or has no event) is not a confirmation")
        void receiptForOtherTradeOrWithoutEvent() {
            chain.receiptStyle = ReceiptStyle.OTHER_TRADE_EVENT;
            assertThat(buy("1").outcome()).isEqualTo("NEEDS_REVIEW");
            chain.receiptStyle = ReceiptStyle.NO_EVENT;
            assertThat(buy("1").outcome()).isEqualTo("NEEDS_REVIEW");
        }

        @Test
        @DisplayName("D: repeated reconciliation of a confirmed trade changes nothing (idempotent)")
        void duplicateReconciliation() {
            String tradeId = confirmedTrade("2");
            String hash = trade(tradeId).getTxHash();
            assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.SKIPPED);
            assertThat(settlement.submit(tradeId)).isEqualTo(Outcome.SKIPPED);
            assertThat(trade(tradeId).getTxHash()).isEqualTo(hash);
            assertThat(settlement.attempts(tradeId)).hasSize(1);
            assertThat(balance(seller)).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("E: a legacy trade (no stored references) whose on-chain references differ is UNVERIFIABLE, not MISMATCH")
        void legacyReferencesUnverifiable() throws Exception {
            String tradeId = pendingTrade("2");
            jdbc.update("UPDATE trade_transactions SET seller_ref = NULL, buyer_ref = NULL, privacy_key_id = NULL WHERE trade_id = ?", tradeId);
            chain.recordWithOtherRefs(payload(tradeId)); // as if anchored with a key that has since been rotated

            assertThat(settlement.verifyOnChain(tradeId)).isEqualTo("UNVERIFIABLE");
            assertThat(settlement.reconcile(tradeId)).as("not adopted automatically").isEqualTo(Outcome.NEEDS_REVIEW);
            assertThat(trade(tradeId).getReviewReason()).contains("party references cannot be verified");
            // An administrator may confirm: the record digest still proves the record.
            assertThat(recovery.resolveConfirmed("HH-ADMIN", tradeId, REASON).status()).isEqualTo("CONFIRMED");
        }
    }

    // ================================================================== Phase B: audited recovery

    @Nested
    class AdministratorRecovery {

        private String needsReviewAfterReset() {
            String tradeId = submittedTrade("2");
            resetChainAndAcceptNewDeployment();
            assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.NEEDS_REVIEW);
            return tradeId;
        }

        @Test
        @DisplayName("B: resolve-failed needs the prior-deployment attestation, releases escrow and energy once, and is audited")
        void resolveFailed() {
            String tradeId = needsReviewAfterReset();
            assertThatThrownBy(() -> recovery.resolveFailed("HH-ADMIN", tradeId, NO_ATTESTATION))
                    .isInstanceOf(BusinessRuleException.class).hasMessageContaining("priorDeploymentRetired");
            assertThat(trade(tradeId).getStatus()).isEqualTo(TradeStatus.NEEDS_REVIEW);

            var view = recovery.resolveFailed("HH-ADMIN", tradeId, REASON);
            assertThat(view.status()).isEqualTo("FAILED");
            assertThat(balance(buyer)).isEqualByComparingTo("100.00");
            assertThat(remaining()).isEqualTo(5.0);
            assertThat(view.history()).extracting(h -> h.toStatus() + "/" + h.actor()).contains("NEEDS_REVIEW/SYSTEM", "FAILED/ADMIN");
            assertThat(trade(tradeId).getReviewReason()).contains("HH-ADMIN").contains(REASON.reason());
            assertThat(auditLogRepository.findByActorUsername("HH-ADMIN"))
                    .anyMatch(a -> a.getAction().equals("TRADE_RESOLVE_FAILED") && a.getDetails().contains("priorDeploymentRetired=true"));
            assertThat(openAlerts("TRADE_NEEDS_REVIEW", tradeId)).isZero();

            assertThatThrownBy(() -> recovery.resolveFailed("HH-ADMIN", tradeId, REASON))
                    .as("a repeated request cannot change the outcome twice").isInstanceOf(BusinessRuleException.class);
            assertThat(balance(buyer)).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("B: resolve-failed is refused when the trade IS recorded; resolve-confirmed then pays the seller once")
        void resolveConfirmedWithEvidence() {
            String tradeId = pendingTrade("2");
            chain.recordTampered(payload(tradeId));
            assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.NEEDS_REVIEW);
            assertThatThrownBy(() -> recovery.resolveConfirmed("HH-ADMIN", tradeId, REASON))
                    .as("a mismatching record is not evidence").isInstanceOf(BusinessRuleException.class);

            String recorded = pendingTrade("1");
            chain.recordOutOfBand(payload(recorded));
            chain.removeEventLog(payload(recorded).tradeKey());
            assertThat(settlement.reconcile(recorded)).isEqualTo(Outcome.NEEDS_REVIEW);
            assertThatThrownBy(() -> recovery.resolveFailed("HH-ADMIN", recorded, REASON))
                    .isInstanceOf(BusinessRuleException.class).hasMessageContaining("recorded on the current deployment");
            assertThatThrownBy(() -> recovery.resolveConfirmed("HH-ADMIN", recorded, REASON))
                    .as("missing event: still not enough evidence").isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("no TradeRecorded event");
        }

        @Test
        @DisplayName("B: resolve-confirmed with full evidence confirms and pays once; repeat is refused")
        void resolveConfirmedPaysOnce() {
            String tradeId = submittedTrade("2");
            chain.receiptStyle = ReceiptStyle.NO_EVENT;
            chain.mineAllPending();
            assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.NEEDS_REVIEW);
            // the on-chain record and its TradeRecorded log exist; only the receipt view was inconsistent
            chain.receiptStyle = ReceiptStyle.NORMAL;
            String eventHash = chain.recordOutOfBand(payload(tradeId)); // fresh, consistent evidence for the same record

            var view = recovery.resolveConfirmed("HH-ADMIN", tradeId, REASON);
            assertThat(view.status()).isEqualTo("CONFIRMED");
            assertThat(trade(tradeId).getTxHash()).isEqualTo(eventHash);
            assertThat(balance(seller)).isEqualByComparingTo("10.00");
            assertThatThrownBy(() -> recovery.resolveConfirmed("HH-ADMIN", tradeId, REASON)).isInstanceOf(BusinessRuleException.class);
            assertThat(balance(seller)).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("B: re-anchoring a NEEDS_REVIEW trade submits it to the reviewed deployment and confirms on evidence")
        void reanchorNeedsReview() {
            String tradeId = needsReviewAfterReset();
            assertThatThrownBy(() -> recovery.reanchor("HH-ADMIN", tradeId, NO_ATTESTATION)).isInstanceOf(BusinessRuleException.class);

            var view = recovery.reanchor("HH-ADMIN", tradeId, REASON);
            assertThat(view.status()).isEqualTo("CONFIRMED");
            TradeTransaction t = trade(tradeId);
            assertThat(t.getDeploymentId()).isEqualTo(chain.currentDeploymentId());
            assertThat(chain.recordedTradeCount()).isEqualTo(1);
            assertThat(balance(seller)).isEqualByComparingTo("10.00");
            assertThat(settlement.attempts(tradeId)).extracting(SubmissionAttemptView::deploymentId).doesNotHaveDuplicates().hasSize(2);
            assertThat(settlement.attempts(tradeId).get(0).status()).as("retired deployment's attempt").isEqualTo("ABANDONED");
            assertThat(settlement.attempts(tradeId).get(0).detail()).contains("attested by HH-ADMIN");
            assertThatThrownBy(() -> recovery.reanchor("HH-ADMIN", tradeId, REASON)).isInstanceOf(BusinessRuleException.class);
        }

        @Test
        @DisplayName("B/L7: re-anchoring a CONFIRMED trade after a reset restores the anchor without moving money")
        void reanchorConfirmed() throws Exception {
            String tradeId = confirmedTrade("2");
            String oldHash = trade(tradeId).getTxHash();
            resetChainAndAcceptNewDeployment();
            settlement.anchorAudit();
            assertThat(openAlerts("ANCHOR_MISSING", tradeId)).isEqualTo(1);

            var view = recovery.reanchor("HH-ADMIN", tradeId, REASON);
            assertThat(view.status()).isEqualTo("CONFIRMED");
            TradeTransaction t = trade(tradeId);
            assertThat(t.getTxHash()).isNotEqualTo(oldHash);
            assertThat(t.getDeploymentId()).isEqualTo(chain.currentDeploymentId());
            assertThat(balance(seller)).as("not paid twice").isEqualByComparingTo("10.00");
            assertThat(balance(buyer)).isEqualByComparingTo("90.00");
            assertThat(openAlerts("ANCHOR_MISSING", tradeId)).isZero();
            assertThat(settlement.verifyOnChain(tradeId)).isEqualTo("MATCH");
            assertThatThrownBy(() -> recovery.reanchor("HH-ADMIN", tradeId, REASON)).isInstanceOf(BusinessRuleException.class);
        }

        @Test
        @DisplayName("B/F: an approved re-anchor that was fenced or left without a receipt is completed by the anchor audit, once")
        void reanchorRetriedByAudit() throws Exception {
            String fenced = confirmedTrade("1");
            String unmined = confirmedTrade("1");
            resetChainAndAcceptNewDeployment();

            // Another instance holds a live signer lease: the approval is recorded but nothing is signed.
            jdbc.update("UPDATE blockchain_signer_lease SET holder_id = 'instance-other', expires_at = ?",
                    java.time.LocalDateTime.now().plusMinutes(5));
            var view = recovery.reanchor("HH-ADMIN", fenced, REASON);
            assertThat(view.status()).isEqualTo("CONFIRMED");
            assertThat(trade(fenced).getDeploymentId()).isNotEqualTo(chain.currentDeploymentId());
            assertThat(settlement.attempts(fenced)).noneMatch(a -> a.purpose().equals("REANCHOR"));
            assertThatThrownBy(() -> recovery.reanchor("HH-ADMIN", fenced, REASON))
                    .isInstanceOf(BusinessRuleException.class).hasMessageContaining("anchor audit retries it");
            settlement.anchorAudit();
            assertThat(trade(fenced).getDeploymentId()).as("still fenced").isNotEqualTo(chain.currentDeploymentId());

            jdbc.update("UPDATE blockchain_signer_lease SET expires_at = ?", java.time.LocalDateTime.now().minusMinutes(5));
            settlement.anchorAudit();
            assertThat(trade(fenced).getDeploymentId()).isEqualTo(chain.currentDeploymentId());
            assertThat(settlement.verifyOnChain(fenced)).isEqualTo("MATCH");

            // Accepted by the node but not mined: the audit waits and never signs a second transaction.
            chain.mode = Mode.PENDING_FOREVER;
            recovery.reanchor("HH-ADMIN", unmined, REASON);
            settlement.anchorAudit();
            settlement.anchorAudit();
            assertThat(settlement.attempts(unmined)).filteredOn(a -> a.purpose().equals("REANCHOR")).hasSize(1);
            chain.mineAllPending();
            chain.mode = Mode.SUCCESS;
            settlement.anchorAudit();
            assertThat(trade(unmined).getDeploymentId()).isEqualTo(chain.currentDeploymentId());
            assertThat(settlement.verifyOnChain(unmined)).isEqualTo("MATCH");
            assertThat(balance(seller)).as("re-anchoring never moves money").isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("B: recovery fails closed when the blockchain cannot be verified")
        void failClosed() {
            String tradeId = needsReviewAfterReset();
            chain.rpcDown = true;
            assertThatThrownBy(() -> recovery.resolveFailed("HH-ADMIN", tradeId, REASON))
                    .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cannot be verified");
            assertThat(trade(tradeId).getStatus()).isEqualTo(TradeStatus.NEEDS_REVIEW);
        }

        @Test
        @DisplayName("B/G2: routine jobs never submit, expire, confirm or release NEEDS_REVIEW, and do not flood the audit log")
        void routineJobsSkipNeedsReview() {
            String tradeId = pendingTrade("2");
            chain.recordTampered(payload(tradeId));
            assertThat(settlement.reconcile(tradeId)).isEqualTo(Outcome.NEEDS_REVIEW);
            ageReservation(tradeId);
            long auditsBefore = auditsMentioning(tradeId);

            for (int i = 0; i < 5; i++) {
                tradingService.reconcileAll();
                tradingService.expireStaleReservations();
                settlement.anchorAudit();
            }
            assertThat(trade(tradeId).getStatus()).isEqualTo(TradeStatus.NEEDS_REVIEW);
            assertThat(balance(buyer)).isEqualByComparingTo("90.00");
            assertThat(settlement.attempts(tradeId)).as("never submitted").isEmpty();
            assertThat(auditsMentioning(tradeId) - auditsBefore).as("no audit flooding for the held trade").isZero();
            assertThat(alertRepository.findByOpenKey("REVIEW:" + tradeId)).get()
                    .satisfies(a -> assertThat(a.getOccurrences()).isEqualTo(1));
        }
    }

    // ================================================================== Phase G: resilience

    @Nested
    class Resilience {

        @Test
        @DisplayName("G2: an unavailable node raises one de-duplicated alert per cycle, not one audit per open trade")
        void unavailableNodeOneAlert() {
            for (int i = 0; i < 4; i++) {
                pendingTrade("0.5");
            }
            chain.rpcDown = true;
            long before = audits("BLOCKCHAIN_VERIFY");
            for (int i = 0; i < 3; i++) {
                settlement.invalidateVerification();
                tradingService.reconcileAll();
            }
            assertThat(audits("BLOCKCHAIN_VERIFY") - before).isLessThanOrEqualTo(1);
            assertThat(alertRepository.findByOpenKey("NETWORK:RPC_UNAVAILABLE")).isPresent();
        }

        @Test
        @DisplayName("G3: a slow node does not block other purchases; they return PENDING and are submitted later")
        void slowNodeDoesNotBlock() throws Exception {
            chain.broadcastDelayMs = 1500;
            CompletableFuture<TradeResult> slow = CompletableFuture.supplyAsync(() -> buy("1"));
            Thread.sleep(200);
            long start = System.nanoTime();
            TradeResult fast = buy("1");
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(fast.outcome()).isEqualTo("PENDING");
            assertThat(fast.trade().statusDetail()).contains("queued for blockchain submission");
            assertThat(elapsedMs).as("did not wait for the slow broadcast").isLessThan(1200);
            assertThat(slow.get(10, TimeUnit.SECONDS).outcome()).isEqualTo("CONFIRMED");

            chain.broadcastDelayMs = 0;
            tradingService.reconcileAll();
            assertThat(trade(fast.trade().tradeId()).getStatus()).isEqualTo(TradeStatus.CONFIRMED);
        }

        @Test
        @DisplayName("G3: the API distinguishes queued, outcome-unknown and failed states")
        void statusDetails() {
            chain.mode = Mode.PENDING_FOREVER;
            assertThat(buy("1").trade().statusDetail()).contains("outcome not yet known");
            chain.mode = Mode.REVERT;
            TradeResult failed = buy("1");
            assertThat(failed.outcome()).isEqualTo("FAILED");
            assertThat(failed.trade().statusDetail()).isEqualTo("The blockchain transaction was reverted");
        }

        @Test
        @DisplayName("L6: cancelling an offer releases unsubmitted reservations; a submitted trade continues to its outcome")
        void offerCancellationWithInFlightTrades() {
            String submitted = submittedTrade("1");
            String pending = pendingTrade("1");
            var admin = new UsernamePasswordAuthenticationToken(seller, null, List.of(new SimpleGrantedAuthority(Role.ROLE_HOUSEHOLD.name())));
            marketplaceService.cancelOffer(admin, offerId);

            assertThat(trade(pending).getStatus()).isEqualTo(TradeStatus.CANCELLED);
            assertThat(trade(pending).getRejectionReason()).isEqualTo("offer cancelled");
            assertThat(trade(submitted).getStatus()).isEqualTo(TradeStatus.SUBMITTED);
            chain.mineAllPending();
            assertThat(settlement.reconcile(submitted)).isEqualTo(Outcome.CONFIRMED);
            assertThat(balance(buyer)).isEqualByComparingTo(new BigDecimal("95.00"));
        }
    }

    long auditsMentioning(String tradeId) {
        return auditLogRepository.findAll().stream().filter(a -> a.getDetails() != null && a.getDetails().contains(tradeId)).count();
    }

    /** Helper used by nested classes that need several trade IDs. */
    static List<String> ids(TradeResult... results) {
        List<String> ids = new ArrayList<>();
        for (TradeResult r : results) {
            ids.add(r.trade().tradeId());
        }
        return ids;
    }
}
