package com.energytrading.service;

import com.energytrading.dto.AnomalyLogView;
import com.energytrading.dto.AuditLogView;
import com.energytrading.dto.PageResponse;
import com.energytrading.exception.ResourceNotFoundException;
import com.energytrading.model.AnomalyLog;
import com.energytrading.model.AuditLog;
import com.energytrading.model.enums.AnomalyReviewStatus;
import com.energytrading.repository.AnomalyLogRepository;
import com.energytrading.repository.AuditLogRepository;
import com.energytrading.service.anomaly.AnomalyRule;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Paged, read-only access to anomaly and audit logs, plus anomaly review updates. */
@Service
public class MonitoringService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AnomalyLogRepository anomalyLogRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditService auditService;

    public MonitoringService(AnomalyLogRepository anomalyLogRepository, AuditLogRepository auditLogRepository,
                             AuditService auditService) {
        this.anomalyLogRepository = anomalyLogRepository;
        this.auditLogRepository = auditLogRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<AnomalyLogView> anomalies(int page, int size) {
        return PageResponse.from(anomalyLogRepository.findAll(pageRequest(page, size, "createdAt")), MonitoringService::toView);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogView> auditLogs(int page, int size) {
        return PageResponse.from(auditLogRepository.findAll(pageRequest(page, size, "timestamp")), MonitoringService::toView);
    }

    @Transactional
    public AnomalyLogView review(String reviewer, Long anomalyId, AnomalyReviewStatus status) {
        AnomalyLog anomaly = anomalyLogRepository.findById(anomalyId)
                .orElseThrow(() -> new ResourceNotFoundException("Anomaly not found"));
        AnomalyReviewStatus previous = anomaly.getReviewStatus();
        anomaly.setReviewStatus(status);
        auditService.record(reviewer, "ANOMALY_REVIEW", "SUCCESS", "anomalyId=" + anomalyId + " " + previous + "->" + status);
        return toView(anomalyLogRepository.save(anomaly));
    }

    /** The outcome is a fixed property of the rule code (see {@link AnomalyRule}). */
    static AnomalyLogView toView(AnomalyLog a) {
        String outcome = AnomalyRule.fromCode(a.getRuleCode()).map(r -> r.outcome().name()).orElse("UNKNOWN");
        return new AnomalyLogView(a.getId(), a.getRuleCode(), outcome, a.getSeverity().name(), a.getEventType(),
                a.getTargetReferenceId(), a.getDescription(), a.getReviewStatus().name(), a.getCreatedAt());
    }

    static AuditLogView toView(AuditLog a) {
        return new AuditLogView(a.getId(), a.getActorUsername(), a.getAction(), a.getOutcome(), a.getDetails(), a.getTimestamp());
    }

    private static PageRequest pageRequest(int page, int size, String sortField) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc(sortField), Sort.Order.desc("id")));
    }
}
