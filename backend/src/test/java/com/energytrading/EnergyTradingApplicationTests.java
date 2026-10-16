package com.energytrading;

import com.energytrading.model.User;
import com.energytrading.repository.AuditLogRepository;
import com.energytrading.repository.MeterReadingRepository;
import com.energytrading.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Test
    @DisplayName("Verify context loads and /api/health responds UP")
    void contextLoadsAndHealthCheckWorks() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.batch").value("Batch 1: Scaffolding, Database & Domain Model"));
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
}
