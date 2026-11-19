package com.eudistack.audit.infrastructure.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.eudistack.audit.infrastructure.config.logging.MaskingStructuredLogEncoder;
import com.eudistack.audit.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceReadyLoggerTest {

    @Test
    void ready_onAWebRole_logsTheConfiguredExporters() {
        assertReady(WebApplicationType.SERVLET, "writer", true, false, true);
    }

    @Test
    void ready_onANonWebRole_logsTheConfiguredExporters() {
        assertReady(WebApplicationType.NONE, "provisioner", false, false, false);
    }

    @Test
    void ready_consoleEncoderShowsTheKeyValueFields() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("audit.role", "reader")
                .withProperty("spring.application.name", "audit-core")
                .withProperty("management.otlp.tracing.export.enabled", "true")
                .withProperty("management.otlp.metrics.export.enabled", "false")
                .withProperty("management.otlp.logging.export.enabled", "true");
        try (LogCapture capture = LogCapture.attach("com.eudistack.audit.lifecycle")) {
            new ServiceReadyLogger(environment).onApplicationEvent(null);

            ILoggingEvent event = capture.events().get(0);
            assertThat(event.getFormattedMessage()).isEqualTo("Audit service ready");
            String line = console(event);
            assertThat(line).contains("\"role\":\"reader\"", "\"logs\":\"enabled\"");
            assertThat(event.getKeyValuePairs()).noneMatch(pair -> "service.name".equals(pair.key));
        }
    }

    private static void assertReady(WebApplicationType type, String role, boolean traces, boolean metrics,
                                    boolean logs) {
        Class<?>[] sources = type == WebApplicationType.SERVLET
                ? new Class<?>[] {ServiceReadyLogger.class, WebServer.class}
                : new Class<?>[] {ServiceReadyLogger.class};
        try (var context = new SpringApplicationBuilder(sources)
                        .web(type)
                        .properties("spring.main.banner-mode=off", "server.port=0")
                        .run(
                                "--spring.application.name=audit-core",
                                "--audit.role=" + role,
                                "--management.otlp.tracing.export.enabled=" + traces,
                                "--management.otlp.metrics.export.enabled=" + metrics,
                                "--management.otlp.logging.export.enabled=" + logs);
                LogCapture capture = LogCapture.attach("com.eudistack.audit.lifecycle")) {
            assertThat(context.getBean(Environment.class).getProperty("audit.role")).isEqualTo(role);
            context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context,
                    Duration.ZERO));
            assertThat(capture.events()).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getMessage()).isEqualTo("Audit service ready");
                assertThat(LogCapture.value(event, "role")).isEqualTo(role);
                assertThat(event.getKeyValuePairs()).noneMatch(pair -> "service.name".equals(pair.key));
                assertThat(LogCapture.value(event, "traces")).isEqualTo(traces ? "enabled" : "disabled");
                assertThat(LogCapture.value(event, "metrics")).isEqualTo(metrics ? "enabled" : "disabled");
                assertThat(LogCapture.value(event, "logs")).isEqualTo(logs ? "enabled" : "disabled");
                assertThat(event.getKeyValuePairs()).noneMatch(pair -> "version".equals(pair.key));
            });
        }
    }

    @Configuration
    static class WebServer {
        @Bean
        ServletWebServerFactory servletWebServerFactory() {
            return new TomcatServletWebServerFactory(0);
        }
    }

    private static String console(ILoggingEvent event) {
        LoggerContext context = new LoggerContext();
        context.putObject(Environment.class.getName(), new MockEnvironment());
        MaskingStructuredLogEncoder encoder = new MaskingStructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("logstash");
        encoder.start();
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }
}
