package com.eudistack.audit.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * One INFO line once the context is up. {@code ApplicationReadyEvent} is published after every
 * {@code InitializingBean}, including {@link OpenTelemetryAppenderInitializer}, so this line is
 * not among the records Logback drops before the OTLP appender has an SDK.
 */
@Component
public final class AuditReadyLogger {

    private static final Logger LOG = LoggerFactory.getLogger(AuditReadyLogger.class);

    private final Environment environment;
    private final ObjectProvider<BuildProperties> buildInfo;

    public AuditReadyLogger(Environment environment, ObjectProvider<BuildProperties> buildInfo) {
        this.environment = environment;
        this.buildInfo = buildInfo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String version = buildInfo.stream().findFirst().map(BuildProperties::getVersion).orElse("unknown");
        logReady(
                environment.getProperty("audit.role", "all"),
                environment.getProperty("spring.application.name", "audit-core"),
                version,
                environment.getProperty("management.otlp.tracing.export.enabled", Boolean.class, false),
                environment.getProperty("management.otlp.metrics.export.enabled", Boolean.class, false),
                environment.getProperty("management.otlp.logging.export.enabled", Boolean.class, false));
    }

    static void logReady(String role, String serviceName, String version,
                         boolean traces, boolean metrics, boolean logs) {
        LOG.atInfo()
                .addKeyValue("role", role)
                .addKeyValue("service.name", serviceName)
                .addKeyValue("version", version)
                .addKeyValue("traces", traces)
                .addKeyValue("metrics", metrics)
                .addKeyValue("logs", logs)
                .log("audit ready");
    }
}
