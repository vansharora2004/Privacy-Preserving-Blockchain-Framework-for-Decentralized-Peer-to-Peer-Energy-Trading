package com.energytrading.service;

import com.energytrading.model.AuditLog;
import com.energytrading.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Records security-relevant actions. Each entry is written in its own transaction so that it
 * is kept even when the surrounding operation is rejected or rolled back.
 * Callers must not pass passwords, secrets or personal details in {@code details}.
 */
@Service
public class AuditService {

    private static final int MAX_DETAILS = 1000;

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actor, String action, String outcome, String details) {
        String safeDetails = details != null && details.length() > MAX_DETAILS ? details.substring(0, MAX_DETAILS) : details;
        auditLogRepository.save(new AuditLog(actor, action, outcome, safeDetails, currentClientAddress()));
    }

    private static String currentClientAddress() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            return servlet.getRequest().getRemoteAddr();
        }
        return null;
    }
}
