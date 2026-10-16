package com.energytrading.repository;

import com.energytrading.model.EnergyOffer;
import com.energytrading.model.enums.OfferStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EnergyOfferRepository extends JpaRepository<EnergyOffer, Long> {
    Optional<EnergyOffer> findByOfferId(String offerId);
    boolean existsByOfferId(String offerId);
    List<EnergyOffer> findByStatus(OfferStatus status);
    List<EnergyOffer> findBySellerId(Long sellerId);
    List<EnergyOffer> findAllByOrderByCreatedAtDesc();
}
