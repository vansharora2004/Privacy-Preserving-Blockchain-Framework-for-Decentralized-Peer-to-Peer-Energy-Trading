package com.energytrading.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public ResponseEntity<Map<String, Object>> getHealth() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("application", "Privacy-Preserving and Trust-Aware P2P Energy Trading");
        response.put("version", "0.0.1-SNAPSHOT");
        response.put("batch", "Batch 1: Scaffolding, Database & Domain Model");
        response.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(response);
    }
}
