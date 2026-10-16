package com.energytrading.controller;

import com.energytrading.dto.AnomalyLogView;
import com.energytrading.dto.AnomalyReviewRequest;
import com.energytrading.dto.AuditLogView;
import com.energytrading.dto.OfferCheckRequest;
import com.energytrading.dto.OfferCheckResponse;
import com.energytrading.dto.PageResponse;
import com.energytrading.service.MonitoringService;
import com.energytrading.service.OfferPrecheckService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Anomaly logs, audit logs and the offer pre-check. */
@RestController
@RequestMapping("/api")
public class MonitoringController {

    private final MonitoringService monitoringService;
    private final OfferPrecheckService offerPrecheckService;

    public MonitoringController(MonitoringService monitoringService, OfferPrecheckService offerPrecheckService) {
        this.monitoringService = monitoringService;
        this.offerPrecheckService = offerPrecheckService;
    }

    @GetMapping("/anomalies")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUDITOR')")
    public PageResponse<AnomalyLogView> anomalies(@RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return monitoringService.anomalies(page, size);
    }

    @PatchMapping(value = "/anomalies/{id}/review", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public AnomalyLogView review(@PathVariable Long id, @Valid @RequestBody AnomalyReviewRequest request,
                                 Authentication authentication) {
        return monitoringService.review(authentication.getName(), id, request.reviewStatus());
    }

    @GetMapping("/audit-logs")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUDITOR')")
    public PageResponse<AuditLogView> auditLogs(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return monitoringService.auditLogs(page, size);
    }

    /** Evaluates a proposed offer for the authenticated household; no offer is created. */
    @PostMapping(value = "/anomaly-checks/offer", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('HOUSEHOLD')")
    public OfferCheckResponse checkOffer(@Valid @RequestBody OfferCheckRequest request, Authentication authentication) {
        return offerPrecheckService.check(authentication.getName(), request);
    }
}
