package com.energytrading.service;

import com.energytrading.config.MarketplaceProperties;
import com.energytrading.config.MeterValidationProperties;
import com.energytrading.dto.CreateOfferRequest;
import com.energytrading.dto.FindingView;
import com.energytrading.dto.OfferResult;
import com.energytrading.dto.OfferView;
import com.energytrading.dto.OwnOfferView;
import com.energytrading.dto.PageResponse;
import com.energytrading.exception.BusinessRuleException;
import com.energytrading.exception.ResourceNotFoundException;
import com.energytrading.model.EnergyOffer;
import com.energytrading.model.TradeTransaction;
import com.energytrading.model.User;
import com.energytrading.model.enums.OfferStatus;
import com.energytrading.model.enums.TradeStatus;
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
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Energy offers: creation from validated surplus, listing, cancellation and expiry.
 * The seller, source reading, available energy and status are always derived on the server.
 */
@Service
public class MarketplaceService {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceService.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,64}");
    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final EnergyOfferRepository offerRepository;
    private final TradeTransactionRepository tradeRepository;
    private final EnergyAvailabilityService availabilityService;
    private final AnomalyDetectionService anomalyDetectionService;
    private final TradeStateService tradeStateService;
    private final TradeViewMapper viewMapper;
    private final AuditService auditService;
    private final PseudonymService pseudonymService;
    private final AccessPolicy accessPolicy;
    private final MarketplaceProperties properties;
    private final MeterValidationProperties meterProperties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public MarketplaceService(UserRepository userRepository, EnergyOfferRepository offerRepository,
                              TradeTransactionRepository tradeRepository, EnergyAvailabilityService availabilityService,
                              AnomalyDetectionService anomalyDetectionService, TradeStateService tradeStateService,
                              TradeViewMapper viewMapper, AuditService auditService, PseudonymService pseudonymService,
                              AccessPolicy accessPolicy, MarketplaceProperties properties,
                              MeterValidationProperties meterProperties, Clock clock, PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.offerRepository = offerRepository;
        this.tradeRepository = tradeRepository;
        this.availabilityService = availabilityService;
        this.anomalyDetectionService = anomalyDetectionService;
        this.tradeStateService = tradeStateService;
        this.viewMapper = viewMapper;
        this.auditService = auditService;
        this.pseudonymService = pseudonymService;
        this.accessPolicy = accessPolicy;
        this.properties = properties;
        this.meterProperties = meterProperties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Validates an Idempotency-Key header (8-64 characters: letters, digits, '.', '_', ':', '-'). */
    public static String validateIdempotencyKey(String key, boolean required) {
        if (key == null || key.isBlank()) {
            if (required) {
                throw BusinessRuleException.badRequest("The Idempotency-Key header is required for this request");
            }
            return null;
        }
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw BusinessRuleException.badRequest("Idempotency-Key must be 8-64 characters: letters, digits, '.', '_', ':' or '-'");
        }
        return key;
    }

    private record OfferDraft(OfferResult result, List<RuleFinding> findings) {
    }

    public OfferResult createOffer(String sellerCode, CreateOfferRequest request, String idempotencyKey) {
        String key = validateIdempotencyKey(idempotencyKey, false);
        Duration lifetime = lifetime(request.expiresInMinutes());
        User seller = userRepository.findByHouseholdCode(sellerCode)
                .orElseThrow(() -> new ResourceNotFoundException("Household not found"));

        OfferDraft draft = transactionTemplate.execute(tx -> {
            userRepository.lockById(seller.getId()); // serializes this seller's offers: no double-commitment of surplus
            if (key != null) {
                var existing = offerRepository.findBySellerIdAndClientRequestId(seller.getId(), key);
                if (existing.isPresent()) {
                    EnergyOffer o = existing.get();
                    if (EnergyValues.toBigDecimal(o.getQuantityKwh()).compareTo(request.quantityKwh()) != 0
                            || o.getPricePerKwh().compareTo(request.pricePerKwh()) != 0) {
                        throw BusinessRuleException.conflict("Idempotency-Key was already used for a different offer");
                    }
                    return new OfferDraft(new OfferResult("PASSED", true, viewMapper.toOwnOfferView(o), null, List.of()), List.of());
                }
            }
            EnergyAvailabilityService.Availability availability = availabilityService.forSeller(seller);
            List<RuleFinding> findings = new ArrayList<>(anomalyDetectionService.checkQuantity("quantityKwh",
                    request.quantityKwh(), false, meterProperties.maxScale()));
            findings.addAll(anomalyDetectionService.checkPrice(request.pricePerKwh()));
            if (request.quantityKwh().signum() > 0) {
                if (availability.unavailableReason() != null) {
                    findings.add(new RuleFinding(AnomalyRule.OVERSELLING, "quantityKwh", availability.unavailableReason()));
                } else {
                    anomalyDetectionService.checkOverselling(request.quantityKwh(), availability.availableKwh()).ifPresent(findings::add);
                }
            }
            List<FindingView> views = findings.stream().map(FindingView::from).toList();
            String outcome = AnomalyDetectionService.overallOutcome(findings);
            if (RuleOutcome.REJECTED.name().equals(outcome)) {
                return new OfferDraft(new OfferResult(outcome, false, null, availability.availableKwh(), views), findings);
            }
            EnergyOffer offer = new EnergyOffer(seller, availability.sourceReading(), "OF-" + UUID.randomUUID(),
                    EnergyValues.toDouble(request.quantityKwh()), EnergyValues.toDouble(request.quantityKwh()),
                    request.pricePerKwh(), OfferStatus.ACTIVE);
            offer.setExpiresAt(LocalDateTime.now(clock).plus(lifetime));
            offer.setClientRequestId(key);
            offerRepository.save(offer);
            return new OfferDraft(new OfferResult(outcome, false, viewMapper.toOwnOfferView(offer),
                    availability.availableKwh().subtract(request.quantityKwh()), views), findings);
        });

        OfferResult result = draft.result();
        if (!result.replayed()) {
            anomalyDetectionService.record("OFFER_CREATE", pseudonymService.householdPseudonym(sellerCode), null, draft.findings());
            auditService.record(sellerCode, "OFFER_CREATE", result.outcome(),
                    (result.offer() != null ? "offerId=" + result.offer().offerId() + " " : "")
                            + "rules=" + AnomalyDetectionService.codes(draft.findings()));
        }
        return result;
    }

    /**
     * Cancels an open offer (its seller or an administrator). Reservations not yet submitted to the
     * blockchain are released; trades already SUBMITTED continue to their final outcome.
     */
    public OwnOfferView cancelOffer(Authentication authentication, String offerId) {
        EnergyOffer found = offerRepository.findByOfferId(offerId)
                .orElseThrow(() -> new ResourceNotFoundException("Offer not found"));
        OwnOfferView view = transactionTemplate.execute(tx -> {
            // Lock order: trades first, then the offer (same order as every other trade transition)
            List<TradeTransaction> inFlight = tradeRepository
                    .findByOfferIdAndStatusIn(found.getId(), EnumSet.of(TradeStatus.PENDING, TradeStatus.FLAGGED)).stream()
                    .sorted(Comparator.comparing(TradeTransaction::getId))
                    .map(t -> tradeRepository.lockByTradeId(t.getTradeId()).orElseThrow())
                    .toList();
            EnergyOffer offer = offerRepository.lockById(found.getId()).orElseThrow();
            accessPolicy.requireOwnerOrAdmin(authentication, offer.getSeller().getHouseholdCode(), "offer cancellation");
            if (!TradeStateService.OPEN_OFFER.contains(offer.getStatus())) {
                throw BusinessRuleException.conflict("Offer cannot be cancelled in status " + offer.getStatus());
            }
            offer.setStatus(OfferStatus.CANCELLED);
            offerRepository.save(offer);
            for (TradeTransaction trade : inFlight) {
                if (trade.getStatus() == TradeStatus.PENDING || trade.getStatus() == TradeStatus.FLAGGED) {
                    tradeStateService.release(trade, TradeStatus.CANCELLED, authentication.getName(), "offer cancelled");
                }
            }
            return viewMapper.toOwnOfferView(offer);
        });
        auditService.record(authentication.getName(), "OFFER_CANCEL", "SUCCESS", "offerId=" + offerId);
        return view;
    }

    /** Marks open offers past their expiry time as EXPIRED; their unsold energy becomes available again. */
    public int expireOffers() {
        LocalDateTime now = LocalDateTime.now(clock);
        int expired = 0;
        for (EnergyOffer candidate : offerRepository.findByStatusInAndExpiresAtLessThanEqual(TradeStateService.OPEN_OFFER, now)) {
            Boolean changed = transactionTemplate.execute(tx -> {
                EnergyOffer offer = offerRepository.lockById(candidate.getId()).orElseThrow();
                if (!TradeStateService.OPEN_OFFER.contains(offer.getStatus()) || offer.getExpiresAt().isAfter(now)) {
                    return false;
                }
                offer.setStatus(OfferStatus.EXPIRED);
                offerRepository.save(offer);
                return true;
            });
            if (Boolean.TRUE.equals(changed)) {
                expired++;
                auditService.record("SYSTEM", "OFFER_EXPIRE", "SUCCESS", "offerId=" + candidate.getOfferId());
            }
        }
        if (expired > 0) {
            log.info("Expired {} offer(s)", expired);
        }
        return expired;
    }

    @Transactional(readOnly = true)
    public PageResponse<OfferView> openOffers(int page, int size) {
        PageRequest request = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Order.asc("pricePerKwh"), Sort.Order.asc("id")));
        return PageResponse.from(offerRepository.findByStatusInAndExpiresAtAfter(TradeStateService.OPEN_OFFER,
                LocalDateTime.now(clock), request), viewMapper::toOfferView);
    }

    @Transactional(readOnly = true)
    public List<OwnOfferView> offersOf(String sellerCode) {
        User seller = userRepository.findByHouseholdCode(sellerCode)
                .orElseThrow(() -> new ResourceNotFoundException("Household not found"));
        return offerRepository.findBySellerIdOrderByCreatedAtDesc(seller.getId()).stream().map(viewMapper::toOwnOfferView).toList();
    }

    @Transactional(readOnly = true)
    public BigDecimal availableForNewOffers(String sellerCode) {
        User seller = userRepository.findByHouseholdCode(sellerCode)
                .orElseThrow(() -> new ResourceNotFoundException("Household not found"));
        return availabilityService.forSeller(seller).availableKwh();
    }

    private Duration lifetime(Integer expiresInMinutes) {
        if (expiresInMinutes == null) {
            return properties.defaultOfferLifetime();
        }
        Duration requested = Duration.ofMinutes(expiresInMinutes);
        if (requested.compareTo(properties.minOfferLifetime()) < 0 || requested.compareTo(properties.maxOfferLifetime()) > 0) {
            throw BusinessRuleException.badRequest("expiresInMinutes must be between " + properties.minOfferLifetime().toMinutes()
                    + " and " + properties.maxOfferLifetime().toMinutes());
        }
        return requested;
    }
}
