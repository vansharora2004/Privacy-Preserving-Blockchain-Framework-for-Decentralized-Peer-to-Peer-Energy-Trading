package com.energytrading.service;

import com.energytrading.dto.OfferView;
import com.energytrading.dto.OwnOfferView;
import com.energytrading.dto.TradeEventView;
import com.energytrading.dto.TradeView;
import com.energytrading.model.EnergyOffer;
import com.energytrading.model.TradeEvent;
import com.energytrading.model.TradeTransaction;
import com.energytrading.privacy.PseudonymService;
import com.energytrading.repository.TradeEventRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds marketplace views. Households appear only as pseudonyms; trade-history actors are
 * reduced to role labels, so no view exposes a household code, name, e-mail or database ID.
 * Must be called inside a transaction (it reads lazy associations).
 */
@Component
public class TradeViewMapper {

    private final PseudonymService pseudonymService;
    private final TradeEventRepository eventRepository;

    public TradeViewMapper(PseudonymService pseudonymService, TradeEventRepository eventRepository) {
        this.pseudonymService = pseudonymService;
        this.eventRepository = eventRepository;
    }

    public OfferView toOfferView(EnergyOffer o) {
        return new OfferView(o.getOfferId(), pseudonymService.householdPseudonym(o.getSeller().getHouseholdCode()),
                EnergyValues.toBigDecimal(o.getRemainingKwh()), o.getPricePerKwh(), o.getExpiresAt(), o.getCreatedAt());
    }

    public OwnOfferView toOwnOfferView(EnergyOffer o) {
        return new OwnOfferView(o.getOfferId(), o.getStatus().name(), EnergyValues.toBigDecimal(o.getQuantityKwh()),
                EnergyValues.toBigDecimal(o.getRemainingKwh()), o.getPricePerKwh(),
                o.getSourceReading() != null ? o.getSourceReading().getReadingId() : null, o.getCreatedAt(), o.getExpiresAt());
    }

    /** @param viewerCode household code of the viewer (to label their role), or null */
    public TradeView toTradeView(TradeTransaction t, String viewerCode, boolean includeHistory) {
        String sellerCode = t.getSeller().getHouseholdCode();
        String buyerCode = t.getBuyer().getHouseholdCode();
        String role = viewerCode == null ? null : viewerCode.equals(buyerCode) ? "BUYER" : viewerCode.equals(sellerCode) ? "SELLER" : null;
        List<TradeEventView> history = includeHistory
                ? eventRepository.findByTradeIdOrderByIdAsc(t.getId()).stream().map(e -> toEventView(e, sellerCode, buyerCode)).toList()
                : List.of();
        TradeView.Blockchain chain = new TradeView.Blockchain(t.getTxHash(), t.getBlockNumber(), t.getChainId(),
                t.getContractAddress(), t.getSubmissionAttempts(), t.getSubmittedAt(), t.getRecordDigest());
        return new TradeView(t.getTradeId(), t.getOffer() != null ? t.getOffer().getOfferId() : null, role,
                pseudonymService.householdPseudonym(sellerCode), pseudonymService.householdPseudonym(buyerCode),
                EnergyValues.toBigDecimal(t.getQuantityKwh()), t.getPricePerKwh(), t.getTotalAmount(), t.getStatus().name(),
                statusDetail(t), chain, t.getCreatedAt(), t.getUpdatedAt(), t.getConfirmedAt(), history);
    }

    /** Participant-safe status text; technical blockchain detail stays in the attempt history and audit log. */
    static String statusDetail(TradeTransaction t) {
        if (t.getRejectionReason() != null) {
            return t.getRejectionReason();
        }
        return switch (t.getStatus()) {
            case PENDING -> "Energy reserved; queued for blockchain submission";
            case FLAGGED -> "Held for administrator review before submission";
            case SUBMITTED -> "Awaiting blockchain confirmation (outcome not yet known)";
            case NEEDS_REVIEW -> "Held for administrator review";
            default -> null;
        };
    }

    private static TradeEventView toEventView(TradeEvent e, String sellerCode, String buyerCode) {
        String actor = e.getActor();
        String label;
        if (actor == null) {
            label = "SYSTEM";
        } else if (actor.equals(buyerCode)) {
            label = "BUYER";
        } else if (actor.equals(sellerCode)) {
            label = "SELLER";
        } else if (actor.equals("SYSTEM") || actor.equals("BLOCKCHAIN")) {
            label = actor;
        } else {
            label = "ADMIN";
        }
        return new TradeEventView(e.getFromStatus() != null ? e.getFromStatus().name() : null, e.getToStatus().name(),
                label, e.getDetail(), e.getCreatedAt());
    }
}
