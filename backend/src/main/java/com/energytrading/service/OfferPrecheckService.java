package com.energytrading.service;

import com.energytrading.config.MeterValidationProperties;
import com.energytrading.dto.OfferCheckRequest;
import com.energytrading.dto.OfferCheckResponse;
import com.energytrading.exception.ResourceNotFoundException;
import com.energytrading.model.User;
import com.energytrading.repository.UserRepository;
import org.springframework.stereotype.Service;

/**
 * Evaluates a proposed offer for the authenticated household against the overselling, price
 * and quantity rules. Batch 2 only reports the outcome; creating and reserving offers is the
 * Batch 3 marketplace workflow.
 */
@Service
public class OfferPrecheckService {

    private final UserRepository userRepository;
    private final AnomalyDetectionService anomalyDetectionService;
    private final MeterValidationProperties meterProperties;

    public OfferPrecheckService(UserRepository userRepository, AnomalyDetectionService anomalyDetectionService,
                                MeterValidationProperties meterProperties) {
        this.userRepository = userRepository;
        this.anomalyDetectionService = anomalyDetectionService;
        this.meterProperties = meterProperties;
    }

    public OfferCheckResponse check(String householdCode, OfferCheckRequest request) {
        User seller = userRepository.findByHouseholdCode(householdCode)
                .orElseThrow(() -> new ResourceNotFoundException("Household not found"));
        return anomalyDetectionService.evaluateProposedOffer(seller, request.quantityKwh(), request.pricePerKwh(),
                meterProperties.maxScale());
    }
}
