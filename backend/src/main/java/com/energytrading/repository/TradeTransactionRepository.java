package com.energytrading.repository;

import com.energytrading.model.TradeTransaction;
import com.energytrading.model.enums.TradeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
    List<TradeTransaction> findAllByOrderByCreatedAtDesc();
}
