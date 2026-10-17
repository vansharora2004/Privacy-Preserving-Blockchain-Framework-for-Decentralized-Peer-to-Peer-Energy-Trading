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
    private final EnergyAvailabilityService availabilityService;

    public OfferPrecheckService(UserRepository userRepository, AnomalyDetectionService anomalyDetectionService,
                                MeterValidationProperties meterProperties, EnergyAvailabilityService availabilityService) {
        this.userRepository = userRepository;
        this.anomalyDetectionService = anomalyDetectionService;
        this.meterProperties = meterProperties;
        this.availabilityService = availabilityService;
    }

    public OfferCheckResponse check(String householdCode, OfferCheckRequest request) {
        User seller = userRepository.findByHouseholdCode(householdCode)
                .orElseThrow(() -> new ResourceNotFoundException("Household not found"));
        // Same availability rule as real offer creation (latest VALID reading minus committed offers)
        return anomalyDetectionService.evaluateProposedOffer(seller, request.quantityKwh(), request.pricePerKwh(),
                meterProperties.maxScale(), availabilityService.forSeller(seller).availableKwh());
    }
}
