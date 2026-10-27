package com.eudistack.audit.infrastructure.config;

import io.micrometer.core.instrument.Clock;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpGrpcExportConfigTest {

    @Test
    void tracingConfig_buildsGrpcSpanExporter() throws Exception {
        try (SpanExporter exporter = new OtlpGrpcTracingConfig().otlpGrpcSpanExporter("http://127.0.0.1:4317")) {
            assertThat(exporter).isNotNull();
        }
    }

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
