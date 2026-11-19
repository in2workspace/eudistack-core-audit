package com.eudistack.audit.infrastructure.archive;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.eudistack.audit.application.workflow.ArchiveEventsWorkflow;
import com.eudistack.audit.infrastructure.config.AuditReadyLoggerTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArchiveWorkerLogTest {

    @Test
    void runOnce_SuccessfulRun_LogsStartAndFinishWithCountAndDuration() {
        ArchiveEventsWorkflow workflow = mock(ArchiveEventsWorkflow.class);
        when(workflow.run()).thenReturn(List.of(
                new ArchiveEventsWorkflow.DayOutcome("sandbox", LocalDate.parse("2026-01-01"),
                        ArchiveEventsWorkflow.Status.SUCCESS, 4, null),
                new ArchiveEventsWorkflow.DayOutcome("platform", LocalDate.parse("2026-01-01"),
                        ArchiveEventsWorkflow.Status.SKIPPED, 0, null)));
        ListAppender<ILoggingEvent> logs = AuditReadyLoggerTest.attach(ArchiveWorker.class);
        try {
            assertThat(new ArchiveWorker(workflow).runOnce()).isTrue();

            assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("archiving run started", "archiving run finished");
            assertThat(logs.list.get(1).getKeyValuePairs()).extracting(pair -> pair.key + "=" + pair.value)
                    .contains("archived=4", "tenant_days=2", "failures=0", "result=success");
        } finally {
            AuditReadyLoggerTest.detach(ArchiveWorker.class, logs);
        }
    }

    @Test
    void runOnce_WorkflowThrows_KeepsTheErrorAndStillLogsTheStart() {
        ArchiveEventsWorkflow workflow = mock(ArchiveEventsWorkflow.class);
        when(workflow.run()).thenThrow(new IllegalStateException("store down"));
        ListAppender<ILoggingEvent> logs = AuditReadyLoggerTest.attach(ArchiveWorker.class);
        try {
            assertThat(new ArchiveWorker(workflow).runOnce()).isFalse();

            assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("archiving run started",
                            "Archiving run failed; nothing was deleted for the tenant in progress");
            assertThat(logs.list.get(1).getLevel().toString()).isEqualTo("ERROR");
        } finally {
            AuditReadyLoggerTest.detach(ArchiveWorker.class, logs);
        }
    }
}
