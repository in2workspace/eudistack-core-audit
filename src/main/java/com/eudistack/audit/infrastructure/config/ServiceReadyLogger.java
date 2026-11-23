package com.eudistack.audit.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * One INFO line once the process is up, in every role, including one-shot runners.
 *
 * <p>Decisión propia (fase 1). The level follows OBS-20. It runs on {@link ApplicationReadyEvent},
 * after {@code OpenTelemetryAppenderInitializer} has installed the SDK, so the line is not one of
 * the records buffered before install. The exporter flags are the configured properties: this line
 * does not mean the collector accepted anything.
 *
 * <p>{@code service.version} is omitted: the build does not publish {@code BuildProperties}.
 * {@code service.name} is not repeated as a key-value: {@code logging.structured.json.add} already
 * writes that console member, and a second write makes the Logstash encoder throw. The OTLP
 * resource carries the same name.
 */
@Component
public final class ServiceReadyLogger implements ApplicationListener<ApplicationReadyEvent> {

    static final Logger LOG = LoggerFactory.getLogger("com.eudistack.audit.lifecycle");

    private final Environment environment;

    public ServiceReadyLogger(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        LOG.atInfo()
                .addKeyValue("role", environment.getProperty("audit.role", "all"))
                .addKeyValue("traces", configured("management.otlp.tracing.export.enabled"))
                .addKeyValue("metrics", configured("management.otlp.metrics.export.enabled"))
                .addKeyValue("logs", configured("management.otlp.logging.export.enabled"))
                .log("Audit service ready");
    }

    private String configured(String property) {
        return environment.getProperty(property, boolean.class, false) ? "enabled" : "disabled";
    }
}
