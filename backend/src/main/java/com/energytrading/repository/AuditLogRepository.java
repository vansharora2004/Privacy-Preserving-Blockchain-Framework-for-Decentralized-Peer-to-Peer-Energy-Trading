package com.energytrading.repository;

import com.energytrading.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByActorUsername(String actorUsername);
    List<AuditLog> findAllByOrderByTimestampDesc();
}
