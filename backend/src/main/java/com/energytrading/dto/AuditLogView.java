package com.energytrading.dto;

import java.time.LocalDateTime;

/** An audit log entry. The stored client IP address is not exposed through the API. */
public record AuditLogView(Long id, String actor, String action, String outcome, String details, LocalDateTime timestamp) {
}
