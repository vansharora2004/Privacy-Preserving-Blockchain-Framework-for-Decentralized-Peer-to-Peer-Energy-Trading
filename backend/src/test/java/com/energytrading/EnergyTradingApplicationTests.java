package com.energytrading;

import com.energytrading.config.DataInitializer;
import com.energytrading.model.User;
import com.energytrading.repository.AuditLogRepository;
import com.energytrading.repository.MeterReadingRepository;
import com.energytrading.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class EnergyTradingApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MeterReadingRepository meterReadingRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DataInitializer dataInitializer;

    @Test
    @DisplayName("Verify context loads and /api/health responds UP")
    void contextLoadsAndHealthCheckWorks() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.batch").value("Batch 1: Scaffolding, Database & Domain Model"))
                .andExpect(jsonPath("$.database.status").value("UP"))
                .andExpect(jsonPath("$.database.product").value("H2"));
    }

    @Test
    @DisplayName("Verify initial seed data is populated")
    void verifySeedData() {
        assertThat(userRepository.count()).isGreaterThanOrEqualTo(3);

        Optional<User> seller = userRepository.findByHouseholdCode("HH-001");
        assertThat(seller).isPresent();
        assertThat(seller.get().getDisplayAlias()).isEqualTo("SolarHouse Alpha");
        assertThat(seller.get().getCurrentSurplusKwh()).isEqualTo(5.0);

        Optional<User> buyer = userRepository.findByHouseholdCode("HH-002");
        assertThat(buyer).isPresent();
        assertThat(buyer.get().getDisplayAlias()).isEqualTo("GreenVilla Beta");
        assertThat(buyer.get().getCurrentDeficitKwh()).isEqualTo(3.0);

        assertThat(meterReadingRepository.count()).isGreaterThanOrEqualTo(2);
        assertThat(meterReadingRepository.findByReadingId("MR-HH001-0001")).isPresent();

        assertThat(auditLogRepository.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Seeded passwords are BCrypt hashes of the configured demo passwords")
    void seededPasswordsAreHashed() {
        User household = userRepository.findByHouseholdCode("HH-001").orElseThrow();
        assertThat(household.getPasswordHash()).startsWith("$2").isNotEqualTo("test-household-pass");
        assertThat(passwordEncoder.matches("test-household-pass", household.getPasswordHash())).isTrue();

        User admin = userRepository.findByHouseholdCode("HH-ADMIN").orElseThrow();
        assertThat(passwordEncoder.matches("test-admin-pass", admin.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("Re-running the data initializer does not duplicate seed data")
    void dataInitializerIsIdempotent() {
        long users = userRepository.count();
        long readings = meterReadingRepository.count();
        dataInitializer.run();
        assertThat(userRepository.count()).isEqualTo(users);
        assertThat(meterReadingRepository.count()).isEqualTo(readings);
    }

    @Test
    @DisplayName("CORS allows configured origins only")
    void corsAllowsOnlyConfiguredOrigins() throws Exception {
        mockMvc.perform(options("/api/health")
                        .header(HttpHeaders.ORIGIN, "http://localhost:8082")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:8082"));

        mockMvc.perform(options("/api/health")
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Non-API paths require authentication")
    void nonApiPathsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/internal/anything"))
                .andExpect(status().isForbidden());
    }
}
