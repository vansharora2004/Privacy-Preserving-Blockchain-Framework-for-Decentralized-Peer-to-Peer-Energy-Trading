package com.energytrading.repository;

import com.energytrading.model.EnergyOffer;
import com.energytrading.model.enums.OfferStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface EnergyOfferRepository extends JpaRepository<EnergyOffer, Long> {
    Optional<EnergyOffer> findByOfferId(String offerId);
    boolean existsByOfferId(String offerId);
    List<EnergyOffer> findByStatus(OfferStatus status);
    List<EnergyOffer> findBySellerId(Long sellerId);
    List<EnergyOffer> findAllByOrderByCreatedAtDesc();

    /** Row lock (SELECT ... FOR UPDATE) serializing reservations against one offer. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from EnergyOffer o where o.offerId = :offerId")
    Optional<EnergyOffer> lockByOfferId(@Param("offerId") String offerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from EnergyOffer o where o.id = :id")
    Optional<EnergyOffer> lockById(@Param("id") Long id);

    Optional<EnergyOffer> findBySellerIdAndClientRequestId(Long sellerId, String clientRequestId);

    List<EnergyOffer> findBySellerIdOrderByCreatedAtDesc(Long sellerId);

    Page<EnergyOffer> findByStatusInAndExpiresAtAfter(Collection<OfferStatus> statuses, LocalDateTime now, Pageable pageable);

    List<EnergyOffer> findByStatusInAndExpiresAtLessThanEqual(Collection<OfferStatus> statuses, LocalDateTime now);

    /** Total quantity ever offered from one reading. */
    @Query("select coalesce(sum(o.quantityKwh), 0) from EnergyOffer o where o.sourceReading.id = :readingId")
    double sumQuantityBySourceReading(@Param("readingId") Long readingId);

    /** Unsold quantity returned by closed (cancelled/expired/rejected) offers of one reading. */
    @Query("select coalesce(sum(o.remainingKwh), 0) from EnergyOffer o where o.sourceReading.id = :readingId and o.status in :statuses")
    double sumRemainingBySourceReadingAndStatusIn(@Param("readingId") Long readingId, @Param("statuses") Collection<OfferStatus> statuses);
}
