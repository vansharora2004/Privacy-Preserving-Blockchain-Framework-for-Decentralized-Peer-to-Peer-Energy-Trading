package com.energytrading;

import com.energytrading.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Opt-in integration test against a real MySQL server (skipped by default).
 * <p>
 * Apply database/schema.sql first, then run with RUN_MYSQL_IT=true and the usual
 * DB_HOST / DB_PORT / DB_NAME / DB_USERNAME / DB_PASSWORD (or DB_URL) variables.
 * Hibernate runs in {@code validate} mode, so the test fails if the entities and the
 * schema disagree. It never drops or recreates tables; it only seeds demo data when
 * the users table is empty.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${DB_URL:jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:energy_trading}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC}",
        "spring.datasource.username=${DB_USERNAME:root}",
        "spring.datasource.password=${DB_PASSWORD:}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_IT", matches = "true")
class MySqlIntegrationTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("Datasource connects to MySQL and entities validate against the schema")
    void connectsToMySqlAndSchemaValidates() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
        assertThat(userRepository.count()).isGreaterThanOrEqualTo(3);

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database.status").value("UP"))
                .andExpect(jsonPath("$.database.product").value("MySQL"));
    }
}
