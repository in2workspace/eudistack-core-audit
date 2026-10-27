package com.eudistack.audit.infrastructure.config;

import io.micrometer.registry.otlp.OtlpMetricsSender;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtlpGrpcMetricsSenderTest {

    private final OtlpGrpcMetricsSender sender = new OtlpGrpcMetricsSender();

    @Test
    void send_rejectsBlankAddress() {
        OtlpMetricsSender.Request request = OtlpMetricsSender.Request.builder(new byte[0])
                .address(" ")
                .build();

        assertThatThrownBy(() -> sender.send(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OTLP gRPC address is required");
    }

    @Test
    void send_rejectsBinaryHeader() {
        OtlpMetricsSender.Request request = OtlpMetricsSender.Request.builder(new byte[0])
                .address("http://127.0.0.1:1")
                .headers(Map.of("custom-bin", "abc"))
                .build();

        assertThatThrownBy(() -> sender.send(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Binary gRPC metadata not supported");
    }

    @Test
    void send_wrapsCollectorFailure() {
        OtlpMetricsSender.Request request = OtlpMetricsSender.Request.builder(new byte[0])
                .address("http://127.0.0.1:1")
                .headers(Map.of("authorization", "none"))
                .build();

        assertThatThrownBy(() -> sender.send(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to export metrics via OTLP/gRPC");
    }

    @Test
    void send_wrapsCollectorFailureWhenThereAreNoHeaders() {
        OtlpMetricsSender.Request request = OtlpMetricsSender.Request.builder(new byte[0])
                .address("http://127.0.0.1:1")
                .build();

        assertThatThrownBy(() -> sender.send(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to export metrics via OTLP/gRPC");
    }
}
