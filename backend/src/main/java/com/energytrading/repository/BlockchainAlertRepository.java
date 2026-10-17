package com.energytrading.repository;

import com.energytrading.model.BlockchainAlert;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BlockchainAlertRepository extends JpaRepository<BlockchainAlert, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from BlockchainAlert a where a.openKey = :openKey")
    Optional<BlockchainAlert> lockOpen(@Param("openKey") String openKey);

    Optional<BlockchainAlert> findByOpenKey(String openKey);

    List<BlockchainAlert> findByTradeRefAndResolvedAtIsNull(String tradeRef);

    List<BlockchainAlert> findByAlertTypeAndResolvedAtIsNull(String alertType);

    Page<BlockchainAlert> findByResolvedAtIsNull(Pageable pageable);
}
