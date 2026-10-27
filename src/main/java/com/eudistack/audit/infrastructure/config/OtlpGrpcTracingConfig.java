package com.eudistack.audit.infrastructure.config;

import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exports spans over gRPC to the OpenTelemetry Collector on port 4317.
 * Deployed tasks exclude Spring's HTTP {@code OtlpTracingAutoConfiguration}, which would otherwise
 * call {@code management.otlp.tracing.endpoint} on port 4318.
 */
@Configuration
@ConditionalOnProperty(name = "management.otlp.tracing.grpc.enabled", havingValue = "true")
public class OtlpGrpcTracingConfig {

    @Bean
    public SpanExporter otlpGrpcSpanExporter(
            @Value("${management.otlp.tracing.grpc.endpoint}") String endpoint) {
        return OtlpGrpcSpanExporter.builder()
                .setEndpoint(endpoint)
                .build();
    }
}
