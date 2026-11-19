package com.eudistack.audit.infrastructure.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

public class AuditReadyLoggerTest {

    @Test
    void logReady_AfterTheContextIsUp_EmitsRoleServiceNameVersionAndExporters() {
        ListAppender<ILoggingEvent> logs = attach(AuditReadyLogger.class);
        try {
            AuditReadyLogger.logReady("all", "audit-core", "0.1.0", true, true, true);

            assertThat(logs.list).hasSize(1);
            ILoggingEvent event = logs.list.getFirst();
            assertThat(event.getLevel().toString()).isEqualTo("INFO");
            assertThat(event.getFormattedMessage()).isEqualTo("audit ready");
            assertThat(event.getKeyValuePairs()).extracting(pair -> pair.key + "=" + pair.value)
                    .contains("role=all", "service.name=audit-core", "version=0.1.0",
                            "traces=true", "metrics=true", "logs=true");
        } finally {
            detach(AuditReadyLogger.class, logs);
        }
    }

    public static ListAppender<ILoggingEvent> attach(Class<?> type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    public static void detach(Class<?> type, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(type)).detachAppender(appender);
    }
}
