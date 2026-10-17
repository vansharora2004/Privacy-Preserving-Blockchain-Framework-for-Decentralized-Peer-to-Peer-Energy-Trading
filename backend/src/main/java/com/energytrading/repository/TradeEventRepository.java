package com.energytrading.repository;

import com.energytrading.model.TradeEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TradeEventRepository extends JpaRepository<TradeEvent, Long> {
    List<TradeEvent> findByTradeIdOrderByIdAsc(Long tradeId);
}
