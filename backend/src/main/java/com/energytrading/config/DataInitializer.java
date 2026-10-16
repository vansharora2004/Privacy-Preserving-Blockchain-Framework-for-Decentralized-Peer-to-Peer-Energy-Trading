package com.energytrading.config;

import com.energytrading.model.AuditLog;
import com.energytrading.model.MeterReading;
import com.energytrading.model.User;
import com.energytrading.model.enums.ReadingValidationStatus;
import com.energytrading.model.enums.Role;
import com.energytrading.repository.AuditLogRepository;
import com.energytrading.repository.MeterReadingRepository;
import com.energytrading.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final MeterReadingRepository meterReadingRepository;
    private final AuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean seedEnabled;
    private final String householdPassword;
    private final String adminPassword;

    public DataInitializer(UserRepository userRepository,
                           MeterReadingRepository meterReadingRepository,
                           AuditLogRepository auditLogRepository,
                           PasswordEncoder passwordEncoder,
                           @Value("${app.seed.enabled:true}") boolean seedEnabled,
                           @Value("${app.seed.household-password:}") String householdPassword,
                           @Value("${app.seed.admin-password:}") String adminPassword) {
        this.userRepository = userRepository;
        this.meterReadingRepository = meterReadingRepository;
        this.auditLogRepository = auditLogRepository;
        this.passwordEncoder = passwordEncoder;
        this.seedEnabled = seedEnabled;
        this.householdPassword = householdPassword;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!seedEnabled) {
            log.info("Demo data seeding disabled (app.seed.enabled=false).");
            return;
        }
        if (userRepository.count() > 0) {
            log.info("Database already contains users. Skipping demo data initialization.");
            return;
        }

        log.info("Initializing demo households, meter readings, and audit log...");
        String householdSecret = resolvePassword(householdPassword, "DEMO_HOUSEHOLD_PASSWORD");
        String adminSecret = resolvePassword(adminPassword, "DEMO_ADMIN_PASSWORD");

        // 1. Create demo users / households
        User seller = new User(
            "HH-001",
            "SolarHouse Alpha",
            "Alice Sharma",
            "alice@demo.energy",
            passwordEncoder.encode(householdSecret),
            Role.ROLE_HOUSEHOLD,
            BigDecimal.valueOf(500.00),
            5.0, // 5 kWh surplus
            0.0
        );

        User buyer = new User(
            "HH-002",
            "GreenVilla Beta",
            "Bob Verma",
            "bob@demo.energy",
            passwordEncoder.encode(householdSecret),
            Role.ROLE_HOUSEHOLD,
            BigDecimal.valueOf(1000.00),
            0.0,
            3.0  // 3 kWh deficit
        );

        User admin = new User(
            "HH-ADMIN",
            "Grid Overseer",
            "System Administrator",
            "admin@demo.energy",
            passwordEncoder.encode(adminSecret),
            Role.ROLE_ADMIN,
            BigDecimal.valueOf(10000.00),
            0.0,
            0.0
        );

        userRepository.save(seller);
        userRepository.save(buyer);
        userRepository.save(admin);

        // 2. Create initial valid meter readings
        String sellerDigest = calculateSha256("HH-001|MR-HH001-0001|1|8.5|3.5|5.0");
        MeterReading readingSeller = new MeterReading(
            seller,
            "MR-HH001-0001",
            1L,
            8.5,
            3.5,
            5.0,
            LocalDateTime.now().minusHours(1),
            ReadingValidationStatus.VALID,
            sellerDigest
        );

        String buyerDigest = calculateSha256("HH-002|MR-HH002-0001|1|1.0|4.0|-3.0");
        MeterReading readingBuyer = new MeterReading(
            buyer,
            "MR-HH002-0001",
            1L,
            1.0,
            4.0,
            -3.0,
            LocalDateTime.now().minusHours(1),
            ReadingValidationStatus.VALID,
            buyerDigest
        );

        meterReadingRepository.save(readingSeller);
        meterReadingRepository.save(readingBuyer);

        // 3. Create initial audit log entry
        AuditLog seedLog = new AuditLog(
            "SYSTEM",
            "DATA_INITIALIZATION",
            "SUCCESS",
            "Loaded demo households (HH-001, HH-002, HH-ADMIN) and baseline meter readings.",
            "127.0.0.1"
        );
        auditLogRepository.save(seedLog);

        log.info("Demo data initialization completed successfully.");
    }

    /**
     * Returns the configured demo password, or a random one when none is configured,
     * so no default credential is ever hardcoded. Passwords are never logged.
     */
    private String resolvePassword(String configured, String envName) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        log.warn("{} not set; demo account(s) seeded with a random password. "
                + "Set {} before first startup to enable demo logins.", envName, envName);
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    private String calculateSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(encodedhash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
