package com.eudistack.audit.application.workflow;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.eudistack.audit.domain.model.EventOutcome;
import com.eudistack.audit.infrastructure.config.AuditReadyLoggerTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecordEventsWorkflowLogTest {

    @Test
    void logDuplicates_OneDuplicateInTheBatch_LogsTheCountAndNotTheEventId() {
        ListAppender<ILoggingEvent> logs = AuditReadyLoggerTest.attach(RecordEventsWorkflow.class);
        try {
            RecordEventsWorkflow.logDuplicates(List.of(
                    EventOutcome.accepted("accepted-id"),
                    EventOutcome.duplicate("secret-event-id")));

            assertThat(logs.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo("idempotent duplicates");
                assertThat(event.getKeyValuePairs()).extracting(pair -> pair.key + "=" + pair.value)
                        .contains("duplicates=1");
                assertThat(event.getFormattedMessage() + event.getKeyValuePairs()).doesNotContain("secret-event-id");
            });
        } finally {
            AuditReadyLoggerTest.detach(RecordEventsWorkflow.class, logs);
        }
    }
}
