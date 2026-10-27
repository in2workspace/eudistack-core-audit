package com.eudistack.audit.infrastructure.web.controller;

import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code GET /audit/health} on the API port. The dev load balancer and the cd-dev smoke test call this
 * path on port 8080. Actuator stays on the management port (SAD-DEC-010).
 */
@RestController
public class HealthController {

    private final HealthEndpoint health;

    public HealthController(HealthEndpoint health) {
        this.health = health;
    }

    @GetMapping("/audit/health")
    public ResponseEntity<Map<String, String>> health() {
        Status status = health.health().getStatus();
        HttpStatus code = Status.UP.equals(status) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(code).body(Map.of("status", status.getCode()));
    }
}
