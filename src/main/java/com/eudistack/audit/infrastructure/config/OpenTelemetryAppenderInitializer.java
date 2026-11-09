package com.eudistack.audit.infrastructure.config;

import com.eudistack.audit.infrastructure.config.logging.MaskingOpenTelemetryAppender;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;

/**
 * Injects the Spring-managed {@link OpenTelemetry} instance into the Logback appender.
 * Logback starts before the Spring context, so {@link MaskingOpenTelemetryAppender} has no
 * SDK to export to until this runs; every log record emitted meanwhile is silently dropped
 * (buffered up to a limit, then discarded — see OpenTelemetryAppender#install javadoc).
 */
public final class OpenTelemetryAppenderInitializer implements InitializingBean {

    private final OpenTelemetry openTelemetry;

    public OpenTelemetryAppenderInitializer(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        OpenTelemetryAppender.install(openTelemetry);
    }
}
