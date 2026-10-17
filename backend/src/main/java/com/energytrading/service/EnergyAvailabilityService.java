package com.energytrading.service;

import com.energytrading.config.MarketplaceProperties;
import com.energytrading.model.MeterReading;
import com.energytrading.model.User;
import com.energytrading.model.enums.OfferStatus;
import com.energytrading.model.enums.ReadingValidationStatus;
import com.energytrading.repository.EnergyOfferRepository;
import com.energytrading.repository.MeterReadingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Energy a household may still offer for sale.
 * <p>
 * Rule: offers are backed by the household's <b>latest VALID</b> meter reading, which must be
 * at most {@code app.marketplace.max-source-reading-age} old. Its sellable surplus is
 * {@code max(0, generated - consumed)} (from Batch 2 validation; consumed energy is never
 * sellable). Energy already committed to offers from that reading is subtracted:
 * {@code committed = sum(quantity of all its offers) - sum(remaining of its closed offers)},
 * because cancelled, expired and rejected offers return their unsold remainder. Surplus from older
 * readings is not carried forward.
 */
@Service
public class EnergyAvailabilityService {

    static final Set<OfferStatus> CLOSED = EnumSet.of(OfferStatus.CANCELLED, OfferStatus.EXPIRED, OfferStatus.REJECTED);

    private final MeterReadingRepository meterReadingRepository;
    private final EnergyOfferRepository energyOfferRepository;
    private final MarketplaceProperties properties;
    private final Clock clock;

    public EnergyAvailabilityService(MeterReadingRepository meterReadingRepository, EnergyOfferRepository energyOfferRepository,
                                     MarketplaceProperties properties, Clock clock) {
        this.meterReadingRepository = meterReadingRepository;
        this.energyOfferRepository = energyOfferRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param sourceReading      the reading that would back a new offer, or null
     * @param unavailableReason  why nothing can be offered, or null
     */
    public record Availability(MeterReading sourceReading, BigDecimal readingSurplusKwh, BigDecimal committedKwh,
                               BigDecimal availableKwh, String unavailableReason) {
    }

    @Transactional(readOnly = true)
    public Availability forSeller(User seller) {
        MeterReading latest = meterReadingRepository
                .findTopByHouseholdIdAndValidationStatusOrderBySequenceNoDesc(seller.getId(), ReadingValidationStatus.VALID)
                .orElse(null);
        if (latest == null) {
            return new Availability(null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    "No validated meter reading: submit a reading before offering energy");
        }
        if (latest.getReadingTime().isBefore(LocalDateTime.now(clock).minus(properties.maxSourceReadingAge()))) {
            return new Availability(latest, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    "The latest validated reading is older than " + properties.maxSourceReadingAge().toHours()
                            + " hours: submit a new reading before offering energy");
        }
        BigDecimal surplus = kwh(Math.max(0.0, latest.getAvailableKwh()));
        BigDecimal offered = kwh(energyOfferRepository.sumQuantityBySourceReading(latest.getId()));
        BigDecimal returned = kwh(energyOfferRepository.sumRemainingBySourceReadingAndStatusIn(latest.getId(), CLOSED));
        BigDecimal committed = offered.subtract(returned).max(BigDecimal.ZERO);
        BigDecimal available = surplus.subtract(committed).max(BigDecimal.ZERO);
        return new Availability(latest, surplus, committed, available, null);
    }

    /** Sums of DOUBLE columns are rounded back to the 3-decimal (Wh) resolution used everywhere. */
    private static BigDecimal kwh(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
    }
}
