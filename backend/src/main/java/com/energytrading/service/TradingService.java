package com.energytrading.service;

import com.energytrading.config.MarketplaceProperties;
import com.energytrading.config.MeterValidationProperties;
import com.energytrading.dto.FindingView;
import com.energytrading.dto.MarketSummaryView;
import com.energytrading.dto.PageResponse;
import com.energytrading.dto.PublicTradeView;
import com.energytrading.dto.TradeRequest;
import com.energytrading.dto.TradeResult;
import com.energytrading.dto.TradeVerificationView;
import com.energytrading.dto.TradeView;
import com.energytrading.exception.BusinessRuleException;
import com.energytrading.exception.ResourceNotFoundException;
import com.energytrading.model.EnergyOffer;
import com.energytrading.model.TradeTransaction;
import com.energytrading.model.User;
import com.energytrading.model.enums.ReadingValidationStatus;
import com.energytrading.model.enums.Role;
import com.energytrading.model.enums.TradeStatus;
import com.energytrading.privacy.DigestService;
import com.energytrading.privacy.PseudonymService;
import com.energytrading.repository.EnergyOfferRepository;
import com.energytrading.repository.TradeTransactionRepository;
import com.energytrading.repository.UserRepository;
import com.energytrading.security.AccessPolicy;
import com.energytrading.service.anomaly.AnomalyRule;
import com.energytrading.service.anomaly.RuleFinding;
import com.energytrading.service.anomaly.RuleOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Trade workflow: reservation, review, cancellation, blockchain hand-off and queries.
 * <p>
 * Order of a purchase (docs/implementation.md, Phase 6): authenticate and authorize; validate the
 * payload and Idempotency-Key; lock the offer and confirm it is open with enough quantity; run the
 * meter-validation and anomaly rules; reject or flag; reserve energy and escrow funds; submit to the
 * blockchain; return the resulting state. Price, parties and totals come from the server-side offer.
 */
@Service
public class TradingService {

    private static final Logger log = LoggerFactory.getLogger(TradingService.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final EnergyOfferRepository offerRepository;
    private final TradeTransactionRepository tradeRepository;
    private final AnomalyDetectionService anomalyDetectionService;
    private final TradeStateService tradeStateService;
    private final TradeSettlementService settlementService;
    private final TradeViewMapper viewMapper;
    private final DigestService digestService;
    private final PseudonymService pseudonymService;
    private final com.energytrading.blockchain.TradeChainMapper chainMapper;
    private final AuditService auditService;
    private final AccessPolicy accessPolicy;
    private final MarketplaceProperties marketplaceProperties;
    private final MeterValidationProperties meterProperties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public TradingService(UserRepository userRepository, EnergyOfferRepository offerRepository,
                          TradeTransactionRepository tradeRepository, AnomalyDetectionService anomalyDetectionService,
                          TradeStateService tradeStateService, TradeSettlementService settlementService,
                          TradeViewMapper viewMapper, DigestService digestService, PseudonymService pseudonymService,
                          com.energytrading.blockchain.TradeChainMapper chainMapper, AuditService auditService, AccessPolicy accessPolicy, MarketplaceProperties marketplaceProperties,
                          MeterValidationProperties meterProperties, Clock clock, PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.offerRepository = offerRepository;
        this.tradeRepository = tradeRepository;
        this.anomalyDetectionService = anomalyDetectionService;
        this.tradeStateService = tradeStateService;
        this.settlementService = settlementService;
        this.viewMapper = viewMapper;
        this.digestService = digestService;
        this.pseudonymService = pseudonymService;
        this.chainMapper = chainMapper;
        this.auditService = auditService;
        this.accessPolicy = accessPolicy;
        this.marketplaceProperties = marketplaceProperties;
        this.meterProperties = meterProperties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    private record Reservation(String tradeId, TradeStatus status, boolean replayed, List<RuleFinding> findings) {
    }

    // ------------------------------------------------------------------ purchase

    public TradeResult requestTrade(String buyerCode, TradeRequest request, String idempotencyKey) {
        String key = MarketplaceService.validateIdempotencyKey(idempotencyKey, true);
        User buyer = userRepository.findByHouseholdCode(buyerCode)
                .orElseThrow(() -> new ResourceNotFoundException("Household not found"));

        Reservation reservation = transactionTemplate.execute(tx -> {
            EnergyOffer offer = offerRepository.lockByOfferId(request.offerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Offer not found"));
            User lockedBuyer = userRepository.lockById(buyer.getId()).orElseThrow();

            // Idempotency: a retried request returns the trade created by the first attempt.
            var prior = tradeRepository.findByBuyerIdAndClientRequestId(buyer.getId(), key);
            if (prior.isPresent()) {
                TradeTransaction t = prior.get();
                if (!t.getOffer().getOfferId().equals(request.offerId())
                        || EnergyValues.toBigDecimal(t.getQuantityKwh()).compareTo(request.quantityKwh()) != 0) {
                    throw BusinessRuleException.conflict("Idempotency-Key was already used for a different trade request");
                }
                return new Reservation(t.getTradeId(), t.getStatus(), true, List.of());
            }
            if (!TradeStateService.OPEN_OFFER.contains(offer.getStatus())) {
                throw BusinessRuleException.conflict("Offer is not open (status " + offer.getStatus() + ")");
            }
            if (!offer.getExpiresAt().isAfter(LocalDateTime.now(clock))) {
                throw BusinessRuleException.conflict("Offer has expired");
            }
            // Counted under the buyer row lock, so concurrent requests by one buyer cannot both slip past the limit.
            long recentTrades = tradeRepository.countByBuyerIdAndCreatedAtAfter(buyer.getId(), LocalDateTime.now(clock).minusHours(1));

            BigDecimal quantity = request.quantityKwh();
            BigDecimal remaining = EnergyValues.toBigDecimal(offer.getRemainingKwh());
            List<RuleFinding> findings = new ArrayList<>();
            anomalyDetectionService.checkSelfTrade(offer.getSeller().getId(), lockedBuyer.getId()).ifPresent(findings::add);
            findings.addAll(anomalyDetectionService.checkQuantity("quantityKwh", quantity, false, meterProperties.maxScale()));
            anomalyDetectionService.checkOfferRemaining(quantity, remaining).ifPresent(findings::add);
            if (offer.getSourceReading() == null || offer.getSourceReading().getValidationStatus() != ReadingValidationStatus.VALID) {
                findings.add(new RuleFinding(AnomalyRule.UNVERIFIED_SOURCE_READING, "offerId",
                        "The offer is not backed by a validated meter reading"));
            }
            findings.addAll(anomalyDetectionService.checkPrice(offer.getPricePerKwh()));
            anomalyDetectionService.checkTradeFrequency(recentTrades).ifPresent(findings::add);
            BigDecimal total = quantity.multiply(offer.getPricePerKwh()).setScale(2, RoundingMode.HALF_UP);
            if (quantity.signum() > 0) {
                anomalyDetectionService.checkFunds(lockedBuyer.getBalance(), total).ifPresent(findings::add);
            }
            if (findings.stream().anyMatch(f -> f.outcome() == RuleOutcome.REJECTED)) {
                return new Reservation(null, TradeStatus.REJECTED, false, findings);
            }

            TradeStatus status = findings.isEmpty() ? TradeStatus.PENDING : TradeStatus.FLAGGED;
            offer.setRemainingKwh(EnergyValues.toDouble(remaining.subtract(quantity)));
            TradeStateService.refreshOfferStatus(offer);
            offerRepository.save(offer);
            lockedBuyer.setBalance(lockedBuyer.getBalance().subtract(total));
            userRepository.save(lockedBuyer);

            String tradeId = "TR-" + UUID.randomUUID();
            User seller = offer.getSeller();
            TradeTransaction trade = new TradeTransaction(tradeId, offer, lockedBuyer, seller, EnergyValues.toDouble(quantity),
                    offer.getPricePerKwh(), total, status);
            trade.setClientRequestId(key);
            trade.setRecordDigest(digestService.tradeDigest(tradeId, offer.getOfferId(), seller.getHouseholdCode(),
                    buyerCode, quantity, offer.getPricePerKwh(), total));
            // Party references are fixed now, so a later pseudonym-key rotation cannot change what is anchored.
            String[] refs = chainMapper.partyRefs(tradeId, seller.getHouseholdCode(), buyerCode);
            trade.setSellerRef(refs[0]);
            trade.setBuyerRef(refs[1]);
            trade.setPrivacyKeyId(pseudonymService.keyId());
            tradeRepository.save(trade);
            tradeStateService.created(trade, buyerCode, status == TradeStatus.FLAGGED
                    ? "energy reserved, funds escrowed; held for review: " + AnomalyDetectionService.codes(findings)
                    : "energy reserved, funds escrowed");
            return new Reservation(tradeId, status, false, findings);
        });

        if (!reservation.replayed()) {
            anomalyDetectionService.record("TRADE_REQUEST", pseudonymService.householdPseudonym(buyerCode),
                    reservation.tradeId(), reservation.findings());
            auditService.record(buyerCode, "TRADE_REQUEST", reservation.status().name(),
                    "offerId=" + request.offerId() + (reservation.tradeId() != null ? " tradeId=" + reservation.tradeId() : "")
                            + " rules=" + AnomalyDetectionService.codes(reservation.findings()));
            if (reservation.status() == TradeStatus.PENDING) {
                settlementService.submit(reservation.tradeId());
            }
        }
        List<FindingView> findings = reservation.findings().stream().map(FindingView::from).toList();
        if (reservation.tradeId() == null) {
            return new TradeResult(TradeStatus.REJECTED.name(), false, null, findings);
        }
        TradeView view = view(reservation.tradeId(), buyerCode);
        return new TradeResult(view.status(), reservation.replayed(), view, findings);
    }

    // ------------------------------------------------------------------ lifecycle actions

    /** Buyer (or administrator) cancels a reservation that has not been submitted to the blockchain. */
    public TradeView cancel(Authentication authentication, String tradeId) {
        String buyerCode = transactionTemplate.execute(tx -> {
            TradeTransaction t = lock(tradeId);
            accessPolicy.requireOwnerOrAdmin(authentication, t.getBuyer().getHouseholdCode(), "trade cancellation");
            if (t.getStatus() != TradeStatus.PENDING && t.getStatus() != TradeStatus.FLAGGED) {
                throw BusinessRuleException.conflict("Trade cannot be cancelled in status " + t.getStatus()
                        + (t.getStatus() == TradeStatus.SUBMITTED ? " (already sent to the blockchain)" : ""));
            }
            tradeStateService.release(t, TradeStatus.CANCELLED, authentication.getName(), "Cancelled before blockchain submission");
            return t.getBuyer().getHouseholdCode();
        });
        auditService.record(authentication.getName(), "TRADE_CANCEL", "SUCCESS", "tradeId=" + tradeId);
        return view(tradeId, AccessPolicy.hasRole(authentication, Role.ROLE_HOUSEHOLD) ? buyerCode : null);
    }

    /** Administrator approves a FLAGGED trade: it becomes PENDING and is submitted. */
    public TradeView approve(String adminCode, String tradeId) {
        transactionTemplate.executeWithoutResult(tx -> {
            TradeTransaction t = lock(tradeId);
            if (t.getStatus() != TradeStatus.FLAGGED) {
                throw BusinessRuleException.conflict("Only FLAGGED trades can be approved (status " + t.getStatus() + ")");
            }
            tradeStateService.transition(t, TradeStatus.PENDING, adminCode, "approved after review");
        });
        auditService.record(adminCode, "TRADE_APPROVE", "SUCCESS", "tradeId=" + tradeId);
        settlementService.submit(tradeId);
        return view(tradeId, null);
    }

    /** Administrator rejects a FLAGGED trade: the reservation is released. */
    public TradeView reject(String adminCode, String tradeId) {
        transactionTemplate.executeWithoutResult(tx -> {
            TradeTransaction t = lock(tradeId);
            if (t.getStatus() != TradeStatus.FLAGGED) {
                throw BusinessRuleException.conflict("Only FLAGGED trades can be rejected (status " + t.getStatus() + ")");
            }
            tradeStateService.release(t, TradeStatus.REJECTED, adminCode, "Rejected after administrator review");
        });
        auditService.record(adminCode, "TRADE_REJECT", "SUCCESS", "tradeId=" + tradeId);
        return view(tradeId, null);
    }

    /** Administrator triggers reconciliation (or a retry) for one trade. */
    public TradeView reconcile(String adminCode, String tradeId) {
        if (tradeRepository.findByTradeId(tradeId).isEmpty()) {
            throw new ResourceNotFoundException("Trade not found");
        }
        TradeSettlementService.Outcome outcome = settlementService.reconcile(tradeId);
        auditService.record(adminCode, "TRADE_RECONCILE", outcome.name(), "tradeId=" + tradeId);
        return view(tradeId, null);
    }

    /** Releases PENDING and FLAGGED reservations that exceeded their timeout (unless found on-chain). */
    public int expireStaleReservations() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<TradeTransaction> stale = new ArrayList<>(tradeRepository.findByStatusAndUpdatedAtBefore(TradeStatus.PENDING,
                now.minus(marketplaceProperties.reservationTimeout())));
        stale.addAll(tradeRepository.findByStatusAndUpdatedAtBefore(TradeStatus.FLAGGED, now.minus(marketplaceProperties.flaggedReviewTimeout())));
        int expired = 0;
        for (TradeTransaction candidate : stale) {
            // PENDING reservations are expired only when the chain shows no possible outcome; NEEDS_REVIEW
            // and SUBMITTED trades are never selected here.
            if (candidate.getStatus() == TradeStatus.PENDING
                    && settlementService.expiryDecision(candidate.getTradeId()) != TradeSettlementService.ExpiryDecision.EXPIRE) {
                continue;
            }
            Boolean done = transactionTemplate.execute(tx -> {
                TradeTransaction t = lock(candidate.getTradeId());
                if (t.getStatus() != candidate.getStatus() || t.getUpdatedAt().isAfter(candidate.getUpdatedAt())) {
                    return false;
                }
                tradeStateService.release(t, TradeStatus.EXPIRED, "SYSTEM", t.getStatus() == TradeStatus.FLAGGED
                        ? "Expired: review timeout" : "Expired: reservation timeout before blockchain submission");
                return true;
            });
            if (Boolean.TRUE.equals(done)) {
                expired++;
                auditService.record("SYSTEM", "TRADE_EXPIRE", "SUCCESS", "tradeId=" + candidate.getTradeId());
            }
        }
        if (expired > 0) {
            log.info("Expired {} stale reservation(s)", expired);
        }
        return expired;
    }

    public int anchorAudit() {
        return settlementService.anchorAudit();
    }

    /** Retries PENDING submissions and reconciles SUBMITTED trades (NEEDS_REVIEW is never touched). */
    public void reconcileAll() {
        // Verify the network once per cycle; an unavailable chain raises one de-duplicated alert, not one per trade.
        if (!settlementService.isEnabled() || !settlementService.networkReady()) {
            return;
        }
        for (TradeStatus status : List.of(TradeStatus.SUBMITTED, TradeStatus.PENDING)) {
            for (TradeTransaction t : tradeRepository.findByStatus(status)) {
                settlementService.reconcile(t.getTradeId());
            }
        }
    }

    // ------------------------------------------------------------------ queries

    /** Participants (buyer/seller), administrators and auditors may read a trade. */
    @Transactional(readOnly = true)
    public TradeView get(Authentication authentication, String tradeId) {
        TradeTransaction t = tradeRepository.findByTradeId(tradeId)
                .orElseThrow(() -> new ResourceNotFoundException("Trade not found"));
        requireParticipantOrStaff(authentication, t);
        return viewMapper.toTradeView(t, authentication.getName(), true);
    }

    @Transactional(readOnly = true)
    public PageResponse<TradeView> tradesOf(String householdCode, int page, int size) {
        User household = userRepository.findByHouseholdCode(householdCode)
                .orElseThrow(() -> new ResourceNotFoundException("Household not found"));
        return PageResponse.from(tradeRepository.findByParticipant(household.getId(), pageRequest(page, size)),
                t -> viewMapper.toTradeView(t, householdCode, false));
    }

    @Transactional(readOnly = true)
    public PageResponse<TradeView> allTrades(int page, int size) {
        return PageResponse.from(tradeRepository.findAll(pageRequest(page, size)), t -> viewMapper.toTradeView(t, null, false));
    }

    static final int MARKET_SUMMARY_MIN_SELLERS = 3;

    public void requireExists(String tradeId) {
        if (tradeRepository.findByTradeId(tradeId).isEmpty()) {
            throw new ResourceNotFoundException("Trade not found");
        }
    }

    /** Aggregate public figures; quantity and price figures need at least 3 distinct sellers. */
    @Transactional(readOnly = true)
    public MarketSummaryView marketSummary() {
        List<com.energytrading.model.EnergyOffer> open = offerRepository.findByStatusInAndExpiresAtAfter(TradeStateService.OPEN_OFFER,
                LocalDateTime.now(clock), PageRequest.of(0, 10_000)).getContent();
        long sellers = open.stream().map(o -> o.getSeller().getId()).distinct().count();
        long confirmed = tradeRepository.findByStatus(TradeStatus.CONFIRMED).stream()
                .filter(t -> t.getConfirmedAt() != null && t.getConfirmedAt().isAfter(LocalDateTime.now(clock).minusHours(24))).count();
        if (sellers < MARKET_SUMMARY_MIN_SELLERS) {
            return new MarketSummaryView(open.size(), null, null, null, confirmed, MARKET_SUMMARY_MIN_SELLERS, true);
        }
        BigDecimal total = open.stream().map(o -> EnergyValues.toBigDecimal(o.getRemainingKwh())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal min = open.stream().map(com.energytrading.model.EnergyOffer::getPricePerKwh).min(BigDecimal::compareTo).orElse(null);
        BigDecimal max = open.stream().map(com.energytrading.model.EnergyOffer::getPricePerKwh).max(BigDecimal::compareTo).orElse(null);
        return new MarketSummaryView(open.size(), total, min, max, confirmed, MARKET_SUMMARY_MIN_SELLERS, false);
    }

    /** CONFIRMED trades for the public view: amounts and blockchain references, no household identifiers. */
    @Transactional(readOnly = true)
    public PageResponse<PublicTradeView> publicConfirmedTrades(int page, int size) {
        PageRequest request = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("confirmedAt"), Sort.Order.desc("id")));
        return PageResponse.from(tradeRepository.findByStatus(TradeStatus.CONFIRMED, request), t -> new PublicTradeView(
                EnergyValues.toBigDecimal(t.getQuantityKwh()), t.getPricePerKwh(), t.getTxHash(), t.getBlockNumber(),
                t.getConfirmedAt()));
    }

    public TradeVerificationView verify(Authentication authentication, String tradeId) {
        // Access check and view in an explicit transaction (a self-call would bypass @Transactional)
        TradeView view = transactionTemplate.execute(tx -> get(authentication, tradeId));
        try {
            String result = settlementService.verifyOnChain(tradeId);
            auditService.record(authentication.getName(), "TRADE_VERIFY", result, "tradeId=" + tradeId);
            return new TradeVerificationView(tradeId, result, view.status(),
                    "Compares the database record (amounts, per-trade party references, record digest) with the contract. "
                            + "A match shows the record is unchanged since it was anchored; it does not prove physical delivery.");
        } catch (IOException e) {
            return new TradeVerificationView(tradeId, "UNAVAILABLE", view.status(), "Blockchain RPC unavailable");
        }
    }

    private void requireParticipantOrStaff(Authentication authentication, TradeTransaction t) {
        if (AccessPolicy.hasRole(authentication, Role.ROLE_ADMIN) || AccessPolicy.hasRole(authentication, Role.ROLE_AUDITOR)) {
            return;
        }
        String caller = authentication.getName();
        if (AccessPolicy.hasRole(authentication, Role.ROLE_HOUSEHOLD)
                && (caller.equals(t.getBuyer().getHouseholdCode()) || caller.equals(t.getSeller().getHouseholdCode()))) {
            return;
        }
        auditService.record(caller, "ACCESS_DENIED", "DENIED", "resource=trade tradeId=" + t.getTradeId());
        throw new AccessDeniedException("Not permitted to access this trade");
    }

    private TradeTransaction lock(String tradeId) {
        return tradeRepository.lockByTradeId(tradeId).orElseThrow(() -> new ResourceNotFoundException("Trade not found"));
    }

    private TradeView view(String tradeId, String viewerCode) {
        return transactionTemplate.execute(tx -> viewMapper.toTradeView(
                tradeRepository.findByTradeId(tradeId).orElseThrow(), viewerCode, true));
    }

    private static PageRequest pageRequest(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
}
