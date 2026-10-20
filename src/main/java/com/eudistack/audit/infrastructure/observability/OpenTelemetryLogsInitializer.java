package com.eudistack.audit.infrastructure.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;

/**
 * Hands the Spring-managed {@link OpenTelemetry} to the Logback OTLP appender of
 * {@code logback-spring.xml}. Logback starts before the context, so until this runs the appender has
 * no SDK and drops what it receives. Same approach as the Verifier.
 */
public final class OpenTelemetryLogsInitializer implements InitializingBean {

    private final OpenTelemetry openTelemetry;

    public OpenTelemetryLogsInitializer(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        OpenTelemetryAppender.install(openTelemetry);
    }
}
