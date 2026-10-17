package com.energytrading.repository;

import com.energytrading.model.TradeSubmissionAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TradeSubmissionAttemptRepository extends JpaRepository<TradeSubmissionAttempt, Long> {
    List<TradeSubmissionAttempt> findByTradeIdOrderByAttemptNoAsc(Long tradeId);
    Optional<TradeSubmissionAttempt> findByTxHashIgnoreCase(String txHash);
    boolean existsByTradeIdAndDeploymentIdNot(Long tradeId, String deploymentId);
    long countByTradeId(Long tradeId);
}
