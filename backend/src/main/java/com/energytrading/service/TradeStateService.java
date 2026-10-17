package com.energytrading.service;

import com.energytrading.model.EnergyOffer;
import com.energytrading.model.TradeEvent;
import com.energytrading.model.TradeTransaction;
import com.energytrading.model.User;
import com.energytrading.model.enums.OfferStatus;
import com.energytrading.model.enums.TradeStatus;
import com.energytrading.repository.EnergyOfferRepository;
import com.energytrading.repository.TradeEventRepository;
import com.energytrading.repository.TradeTransactionRepository;
import com.energytrading.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * The only place where trade state, offer balances and account balances change. Every method
 * must run inside the caller's transaction ({@link Propagation#MANDATORY}) after the trade row has
 * been locked; it then locks the offer and the affected account. Lock order is always
 * trade, then offer, then user, which prevents lock-order deadlocks.
 * <p>
 * Money: the buyer's funds are escrowed (deducted) at reservation, refunded on release, and paid
 * to the seller only on confirmation. Energy: reserved from {@code offer.remainingKwh} and
 * returned on release.
 */
@Service
public class TradeStateService {

    /** States that hold reserved energy and escrowed funds. */
    public static final Set<TradeStatus> HOLDING = EnumSet.of(TradeStatus.PENDING, TradeStatus.FLAGGED, TradeStatus.SUBMITTED,
            TradeStatus.NEEDS_REVIEW);
    public static final Set<OfferStatus> OPEN_OFFER = EnumSet.of(OfferStatus.ACTIVE, OfferStatus.PARTIALLY_FILLED);

    private final TradeTransactionRepository tradeRepository;
    private final EnergyOfferRepository offerRepository;
    private final UserRepository userRepository;
    private final TradeEventRepository eventRepository;
    private final Clock clock;

    public TradeStateService(TradeTransactionRepository tradeRepository, EnergyOfferRepository offerRepository,
                             UserRepository userRepository, TradeEventRepository eventRepository, Clock clock) {
        this.tradeRepository = tradeRepository;
        this.offerRepository = offerRepository;
        this.userRepository = userRepository;
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void transition(TradeTransaction trade, TradeStatus to, String actor, String detail) {
        TradeStatus from = trade.getStatus();
        trade.setStatus(to);
        tradeRepository.save(trade);
        eventRepository.save(new TradeEvent(trade, from, to, actor, detail));
    }

    /** Records the initial event of a newly reserved trade. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void created(TradeTransaction trade, String actor, String detail) {
        eventRepository.save(new TradeEvent(trade, null, trade.getStatus(), actor, detail));
    }

    /**
     * Returns the reserved energy to the offer and the escrowed funds to the buyer, then moves the
     * trade to a terminal state ({@code FAILED}, {@code CANCELLED}, {@code EXPIRED} or {@code REJECTED}).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(TradeTransaction trade, TradeStatus to, String actor, String reason) {
        if (!HOLDING.contains(trade.getStatus())) {
            throw new IllegalStateException("Trade " + trade.getTradeId() + " holds no reservation in state " + trade.getStatus());
        }
        EnergyOffer offer = offerRepository.lockById(trade.getOffer().getId()).orElseThrow();
        BigDecimal restored = EnergyValues.toBigDecimal(offer.getRemainingKwh())
                .add(EnergyValues.toBigDecimal(trade.getQuantityKwh()))
                .min(EnergyValues.toBigDecimal(offer.getQuantityKwh()));
        offer.setRemainingKwh(EnergyValues.toDouble(restored));
        refreshOfferStatus(offer);
        offerRepository.save(offer);

        User buyer = userRepository.lockById(trade.getBuyer().getId()).orElseThrow();
        buyer.setBalance(buyer.getBalance().add(trade.getTotalAmount()));
        userRepository.save(buyer);

        trade.setRejectionReason(truncate(reason));
        transition(trade, to, actor, "reservation released: " + reason);
    }

    /** Pays the seller and marks the trade CONFIRMED (only after a successful blockchain receipt). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirm(TradeTransaction trade, String txHash, long blockNumber, String detail) {
        if (trade.getStatus() != TradeStatus.SUBMITTED && trade.getStatus() != TradeStatus.PENDING
                && trade.getStatus() != TradeStatus.NEEDS_REVIEW) {
            throw new IllegalStateException("Trade " + trade.getTradeId() + " cannot be confirmed from " + trade.getStatus());
        }
        User seller = userRepository.lockById(trade.getSeller().getId()).orElseThrow();
        seller.setBalance(seller.getBalance().add(trade.getTotalAmount()));
        userRepository.save(seller);
        trade.setTxHash(txHash);
        trade.setBlockNumber(blockNumber);
        trade.setConfirmedAt(LocalDateTime.now(clock));
        trade.setRejectionReason(null);
        transition(trade, TradeStatus.CONFIRMED, "BLOCKCHAIN", detail);
    }

    /** ACTIVE (nothing reserved or sold), PARTIALLY_FILLED or FILLED; closed offers keep their status. */
    public static void refreshOfferStatus(EnergyOffer offer) {
        if (offer.getStatus() != OfferStatus.ACTIVE && offer.getStatus() != OfferStatus.PARTIALLY_FILLED
                && offer.getStatus() != OfferStatus.FILLED) {
            return;
        }
        BigDecimal remaining = EnergyValues.toBigDecimal(offer.getRemainingKwh());
        if (remaining.signum() <= 0) {
            offer.setStatus(OfferStatus.FILLED);
        } else if (remaining.compareTo(EnergyValues.toBigDecimal(offer.getQuantityKwh())) >= 0) {
            offer.setStatus(OfferStatus.ACTIVE);
        } else {
            offer.setStatus(OfferStatus.PARTIALLY_FILLED);
        }
    }

    private static String truncate(String text) {
        return text != null && text.length() > 255 ? text.substring(0, 255) : text;
    }
}
