package com.energytrading.repository;

import com.energytrading.model.AnomalyLog;
import com.energytrading.model.enums.AnomalyReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AnomalyLogRepository extends JpaRepository<AnomalyLog, Long> {
    List<AnomalyLog> findByRuleCode(String ruleCode);
    List<AnomalyLog> findByTargetReferenceId(String targetReferenceId);
    List<AnomalyLog> findByReviewStatus(AnomalyReviewStatus reviewStatus);
    List<AnomalyLog> findAllByOrderByCreatedAtDesc();
}
