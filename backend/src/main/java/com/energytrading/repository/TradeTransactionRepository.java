package com.energytrading.repository;

import com.energytrading.model.TradeTransaction;
import com.energytrading.model.enums.TradeStatus;
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
public interface TradeTransactionRepository extends JpaRepository<TradeTransaction, Long> {
    Optional<TradeTransaction> findByTradeId(String tradeId);
    boolean existsByTradeId(String tradeId);
    Optional<TradeTransaction> findByTxHash(String txHash);
    List<TradeTransaction> findByBuyerId(Long buyerId);
    List<TradeTransaction> findBySellerId(Long sellerId);
    List<TradeTransaction> findByStatus(TradeStatus status);
    Page<TradeTransaction> findByStatus(TradeStatus status, Pageable pageable);
    List<TradeTransaction> findAllByOrderByCreatedAtDesc();

    /** Row lock (SELECT ... FOR UPDATE) serializing every state transition of one trade. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TradeTransaction t where t.tradeId = :tradeId")
    Optional<TradeTransaction> lockByTradeId(@Param("tradeId") String tradeId);

    Optional<TradeTransaction> findByBuyerIdAndClientRequestId(Long buyerId, String clientRequestId);

    long countByBuyerIdAndCreatedAtAfter(Long buyerId, LocalDateTime since);

    @Query("select t from TradeTransaction t where t.buyer.id = :householdId or t.seller.id = :householdId")
    Page<TradeTransaction> findByParticipant(@Param("householdId") Long householdId, Pageable pageable);

    List<TradeTransaction> findByStatusAndUpdatedAtBefore(TradeStatus status, LocalDateTime before);

    List<TradeTransaction> findByOfferIdAndStatusIn(Long offerId, Collection<TradeStatus> statuses);

    /** CONFIRMED trades for the anchor audit, least recently checked first. */
    @Query("select t from TradeTransaction t where t.status = :status order by case when t.anchorCheckedAt is null then 0 else 1 end, t.anchorCheckedAt asc, t.id asc")
    List<TradeTransaction> findForAnchorAudit(@Param("status") TradeStatus status, Pageable pageable);

    /** CONFIRMED trades with an administrator-approved re-anchor to the given deployment. */
    List<TradeTransaction> findByStatusAndReanchorDeploymentIdIgnoreCase(TradeStatus status, String reanchorDeploymentId, Pageable pageable);
}
