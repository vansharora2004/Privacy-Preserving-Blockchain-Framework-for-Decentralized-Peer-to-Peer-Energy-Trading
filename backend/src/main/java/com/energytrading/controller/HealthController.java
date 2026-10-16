package com.energytrading.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private static final int DB_CHECK_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getHealth() {
        Map<String, Object> database = checkDatabase();
        boolean databaseUp = "UP".equals(database.get("status"));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", databaseUp ? "UP" : "DOWN");
        response.put("application", "Privacy-Preserving and Trust-Aware P2P Energy Trading");
        response.put("version", "0.0.1-SNAPSHOT");
        response.put("batch", "Batch 2: Meter Validation, Privacy & Anomaly Detection");
        response.put("database", database);
        response.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(databaseUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    private Map<String, Object> checkDatabase() {
        Map<String, Object> database = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(DB_CHECK_TIMEOUT_SECONDS);
            database.put("status", valid ? "UP" : "DOWN");
            database.put("product", connection.getMetaData().getDatabaseProductName());
            database.put("productVersion", connection.getMetaData().getDatabaseProductVersion());
        } catch (SQLException e) {
            // Do not echo connection details (URL, user) to unauthenticated callers
            database.put("status", "DOWN");
            database.put("error", "Database connection unavailable");
        }
        return database;
    }
}
