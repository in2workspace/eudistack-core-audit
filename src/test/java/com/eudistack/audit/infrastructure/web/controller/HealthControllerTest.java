package com.eudistack.audit.infrastructure.web.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    private final HealthEndpoint endpoint = mock(HealthEndpoint.class);
    private final HealthController controller = new HealthController(endpoint);

    @Test
    void health_up_is200() {
        when(endpoint.health()).thenReturn(Health.up().build());

        ResponseEntity<Map<String, String>> response = controller.health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @Test
    void health_down_is503() {
        when(endpoint.health()).thenReturn(Health.down().build());

        ResponseEntity<Map<String, String>> response = controller.health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", "DOWN");
    }
}
