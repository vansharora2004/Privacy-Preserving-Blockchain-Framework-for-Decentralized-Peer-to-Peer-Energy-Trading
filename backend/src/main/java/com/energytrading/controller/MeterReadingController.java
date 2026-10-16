package com.energytrading.controller;

import com.energytrading.dto.IntegrityCheckResponse;
import com.energytrading.dto.MeterReadingSubmission;
import com.energytrading.dto.MeterReadingSubmissionResponse;
import com.energytrading.privacy.PrivacyService;
import com.energytrading.service.MeterReadingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/meter-readings")
public class MeterReadingController {

    private final MeterReadingService meterReadingService;
    private final PrivacyService privacyService;

    public MeterReadingController(MeterReadingService meterReadingService, PrivacyService privacyService) {
        this.meterReadingService = meterReadingService;
        this.privacyService = privacyService;
    }

    /**
     * Submits a reading for the authenticated household.
     * 201 = VALID, 202 = FLAGGED (stored for review), 422 = REJECTED (not stored).
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('HOUSEHOLD')")
    public ResponseEntity<MeterReadingSubmissionResponse> submit(@RequestBody MeterReadingSubmission submission,
                                                                 Authentication authentication) {
        MeterReadingSubmissionResponse response = meterReadingService.submit(authentication.getName(), submission);
        HttpStatus status = switch (response.status()) {
            case "VALID" -> HttpStatus.CREATED;
            case "FLAGGED" -> HttpStatus.ACCEPTED;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/{readingId}/integrity")
    @PreAuthorize("hasAnyRole('HOUSEHOLD', 'ADMIN', 'AUDITOR')")
    public IntegrityCheckResponse integrity(@PathVariable String readingId, Authentication authentication) {
        return privacyService.verifyReadingIntegrity(authentication, readingId);
    }
}
