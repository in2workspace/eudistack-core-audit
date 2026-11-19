package com.eudistack.audit.application.workflow;

import java.time.LocalDate;

/**
 * Operational lines of one archive run. The workflow calls them inside the
 * {@code audit.archive.run} span, so the OTLP record picks up that trace from the current context.
 * Decisión propia (fase 1); the span itself is design §3.10.
 */
public interface ArchiveRunLog {

    ArchiveRunLog NONE = new ArchiveRunLog() {
        @Override
        public void started(LocalDate cutoffDay, int scopes) {
            // tests and callers that do not emit the operational line
        }

        @Override
        public void finished(int tenantDays, long failures, long durationMs, String result) {
            // tests and callers that do not emit the operational line
        }
    };

    void started(LocalDate cutoffDay, int scopes);

    void finished(int tenantDays, long failures, long durationMs, String result);
}
