package com.eudistack.audit.infrastructure.web;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.eudistack.audit.domain.exception.BadRequestException;
import com.eudistack.audit.domain.exception.EventNotFoundException;
import com.eudistack.audit.domain.exception.RateLimitedException;
import com.eudistack.audit.domain.exception.ServiceUnavailableException;
import com.eudistack.audit.infrastructure.config.AuditReadyLoggerTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestRejectionLogTest {

    @Test
    void log_CursorAndRateLimit_WarnsWithoutTheExceptionText() {
        ListAppender<ILoggingEvent> logs = AuditReadyLoggerTest.attach(RequestRejectionLog.class);
        try {
            RequestRejectionLog.log(new BadRequestException("cursor", "user@example.com leaked"));
            RequestRejectionLog.log(new RateLimitedException(30));
            RequestRejectionLog.log(new ServiceUnavailableException("db down", null));

            assertThat(logs.list).extracting(event -> event.getLevel() + " " + value(event, "problem"))
                    .containsExactly("WARN cursor_invalid", "WARN rate_limited");
            assertThat(logs.list).allSatisfy(event ->
                    assertThat(event.getFormattedMessage() + event.getKeyValuePairs())
                            .doesNotContain("user@example.com", "db down"));
        } finally {
            AuditReadyLoggerTest.detach(RequestRejectionLog.class, logs);
        }
    }

    @Test
    void log_MissingEvent_IsInfo() {
        ListAppender<ILoggingEvent> logs = AuditReadyLoggerTest.attach(RequestRejectionLog.class);
        try {
            RequestRejectionLog.log(new EventNotFoundException("not here"));

            assertThat(logs.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel().toString()).isEqualTo("INFO");
                assertThat(value(event, "problem")).isEqualTo("event_not_found");
            });
        } finally {
            AuditReadyLoggerTest.detach(RequestRejectionLog.class, logs);
        }
    }

    private static String value(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
                .filter(pair -> key.equals(pair.key))
                .map(pair -> String.valueOf(pair.value))
                .findFirst()
                .orElse("");
    }
}
