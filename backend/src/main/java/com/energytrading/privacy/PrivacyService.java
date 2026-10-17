package com.energytrading.privacy;

import com.energytrading.dto.IntegrityCheckResponse;
import com.energytrading.dto.IntegrityCheckResponse.IntegrityResult;
import com.energytrading.dto.PageResponse;
import com.energytrading.dto.PrivateHouseholdView;
import com.energytrading.dto.PrivateMeterReadingView;
import com.energytrading.dto.PublicHouseholdView;
import com.energytrading.exception.ResourceNotFoundException;
import com.energytrading.model.MeterReading;
import com.energytrading.model.User;
import com.energytrading.model.enums.Role;
import com.energytrading.repository.MeterReadingRepository;
import com.energytrading.repository.UserRepository;
import com.energytrading.security.AccessPolicy;
import com.energytrading.service.AuditService;
import com.energytrading.service.EnergyAvailabilityService;
import com.energytrading.service.EnergyValues;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Builds the public (pseudonymous) and private household views and performs integrity checks.
 * <p>
 * Public views contain only a pseudonym and the validated sellable surplus. Private views are
 * returned only after {@link AccessPolicy} confirms ownership or an administrator role. JPA
 * entities are never returned directly, and password hashes are never part of any view.
 */
@Service
public class PrivacyService {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final MeterReadingRepository meterReadingRepository;
    private final PseudonymService pseudonymService;
    private final DigestService digestService;
    private final AccessPolicy accessPolicy;
    private final AuditService auditService;
    private final Clock clock;
    private final EnergyAvailabilityService availabilityService;

    public PrivacyService(UserRepository userRepository, MeterReadingRepository meterReadingRepository,
                          PseudonymService pseudonymService, DigestService digestService,
                          AccessPolicy accessPolicy, AuditService auditService, Clock clock,
                          EnergyAvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
        this.userRepository = userRepository;
        this.meterReadingRepository = meterReadingRepository;
        this.pseudonymService = pseudonymService;
        this.digestService = digestService;
        this.accessPolicy = accessPolicy;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * Households (role HOUSEHOLD only) as pseudonyms. Energy availability is deliberately not published
     * per household (it would track live generation and consumption).
     */
    @Transactional(readOnly = true)
    public List<PublicHouseholdView> publicHouseholds() {
        return userRepository.findByRoleOrderByHouseholdCodeAsc(Role.ROLE_HOUSEHOLD).stream()
                .map(u -> new PublicHouseholdView(pseudonymService.householdPseudonym(u.getHouseholdCode())))
                .toList();
    }

    @Transactional(readOnly = true)
    public PrivateHouseholdView privateHousehold(Authentication authentication, String householdCode) {
        accessPolicy.requireOwnerOrAdmin(authentication, householdCode, "private household profile");
        return toPrivateView(findHousehold(householdCode));
    }

    /** The caller's own profile; every authenticated account may read itself. */
    @Transactional(readOnly = true)
    public PrivateHouseholdView ownProfile(Authentication authentication) {
        return toPrivateView(findHousehold(authentication.getName()));
    }

    /** All accounts, for administrators (the controller restricts this to ROLE_ADMIN). */
    @Transactional(readOnly = true)
    public List<PrivateHouseholdView> allHouseholds() {
        return userRepository.findAllByOrderByHouseholdCodeAsc().stream().map(this::toPrivateView).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<PrivateMeterReadingView> householdReadings(Authentication authentication, String householdCode,
                                                                   int page, int size) {
        accessPolicy.requireOwnerOrAdmin(authentication, householdCode, "meter readings");
        User household = findHousehold(householdCode);
        PageRequest request = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "sequenceNo"));
        return PageResponse.from(meterReadingRepository.findByHouseholdId(household.getId(), request), PrivacyService::toReadingView);
    }

    /**
     * Recomputes the canonical SHA-256 digest of a stored reading and compares it with the stored
     * digest. Allowed for the owning household, administrators and auditors. Only the verdict is
     * returned, never the reading contents.
     */
    @Transactional(readOnly = true)
    public IntegrityCheckResponse verifyReadingIntegrity(Authentication authentication, String readingId) {
        MeterReading reading = meterReadingRepository.findByReadingId(readingId)
                .orElseThrow(() -> new ResourceNotFoundException("Meter reading not found"));
        String householdCode = reading.getHousehold().getHouseholdCode();
        accessPolicy.requireOwnerOr(authentication, householdCode, "reading integrity check", Role.ROLE_ADMIN, Role.ROLE_AUDITOR);

        IntegrityResult result;
        String format = null;
        if (reading.getRecordHash() == null || reading.getRecordHash().isBlank()) {
            result = IntegrityResult.NO_STORED_DIGEST;
        } else {
            String current = digestService.meterReadingDigest(householdCode, reading.getReadingId(), reading.getSequenceNo(),
                    EnergyValues.toBigDecimal(reading.getEnergyGeneratedKwh()), EnergyValues.toBigDecimal(reading.getEnergyConsumedKwh()),
                    EnergyValues.toBigDecimal(reading.getAvailableKwh()), reading.getReadingTime());
            String legacy = digestService.legacyMeterReadingDigest(householdCode, reading.getReadingId(), reading.getSequenceNo(),
                    reading.getEnergyGeneratedKwh(), reading.getEnergyConsumedKwh(), reading.getAvailableKwh());
            if (digestService.matches(reading.getRecordHash(), current)) {
                result = IntegrityResult.MATCH;
                format = DigestService.METER_READING_V1;
            } else if (digestService.matches(reading.getRecordHash(), legacy)) {
                result = IntegrityResult.MATCH;
                format = DigestService.METER_READING_LEGACY_V0;
            } else {
                result = IntegrityResult.MISMATCH;
            }
        }
        auditService.record(authentication.getName(), "INTEGRITY_CHECK", result.name(), "sequenceNo=" + reading.getSequenceNo());
        return new IntegrityCheckResponse(reading.getReadingId(), result, format, LocalDateTime.now(clock),
                "Detects changes made after the digest was stored. It does not prove the original data was truthful, "
                        + "and it cannot detect an actor who can modify both the record and its stored digest.");
    }

    private User findHousehold(String householdCode) {
        return userRepository.findByHouseholdCode(householdCode)
                .orElseThrow(() -> new ResourceNotFoundException("Household not found"));
    }

    private PrivateHouseholdView toPrivateView(User u) {
        return new PrivateHouseholdView(u.getHouseholdCode(), pseudonymService.householdPseudonym(u.getHouseholdCode()),
                u.getDisplayAlias(), u.getFullName(), u.getEmail(), u.getRole().name(), u.getBalance(),
                EnergyValues.toBigDecimal(u.getCurrentSurplusKwh()), EnergyValues.toBigDecimal(u.getCurrentDeficitKwh()),
                u.getCreatedAt());
    }

    private static PrivateMeterReadingView toReadingView(MeterReading r) {
        return new PrivateMeterReadingView(r.getReadingId(), r.getSequenceNo(),
                EnergyValues.toBigDecimal(r.getEnergyGeneratedKwh()), EnergyValues.toBigDecimal(r.getEnergyConsumedKwh()),
                EnergyValues.toBigDecimal(r.getAvailableKwh()), r.getReadingTime(), r.getValidationStatus().name(),
                r.getRejectionReason(), r.getRecordHash(), r.getCreatedAt());
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
