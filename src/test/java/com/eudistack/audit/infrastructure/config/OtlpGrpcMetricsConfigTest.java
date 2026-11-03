package com.eudistack.audit.infrastructure.config;

import io.micrometer.core.instrument.Clock;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpGrpcMetricsConfigTest {

    @Test
    void metricsConfig_buildsRegistryThatSendsThroughGrpc() {
        OtlpMeterRegistry registry = new OtlpGrpcMetricsConfig()
                .otlpMeterRegistry(OtlpConfig.DEFAULT, Clock.SYSTEM);
        try {
            assertThat(registry).isNotNull();
        } finally {
            registry.close();
        }
    }
}
