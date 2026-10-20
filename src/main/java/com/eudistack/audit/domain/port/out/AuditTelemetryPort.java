package com.eudistack.audit.domain.port.out;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Metrics and spans of design §3.10, without tying the use cases to Micrometer.
 */
public interface AuditTelemetryPort {

    /**
     * Runs the work inside a span with the given attributes (high cardinality: spans only, never labels).
     */
    <T> T span(String name, Map<String, String> attributes, Supplier<T> work);

    void batchRecorded(String outcome, long nanos);

    void eventRecorded(String status, String error);

    void catalogueValidated(boolean valid, long nanos);

    void queryServed(String operation, String outcome, long nanos);

    void rateLimited(boolean system, boolean search);

    void writeDenied(String reason);

    void archiveRun(boolean success);

    void archiveLag(String tenantId, boolean platform, long seconds);

    /**
     * Telemetry that records nothing, for tests and one-shot tasks.
     */
    static AuditTelemetryPort noop() {
        return new AuditTelemetryPort() {
            @Override
            public <T> T span(String name, Map<String, String> attributes, Supplier<T> work) {
                return work.get();
            }

            @Override
            public void batchRecorded(String outcome, long nanos) {
                // nothing to record
            }

            @Override
            public void eventRecorded(String status, String error) {
                // nothing to record
            }

            @Override
            public void catalogueValidated(boolean valid, long nanos) {
                // nothing to record
            }

            @Override
            public void queryServed(String operation, String outcome, long nanos) {
                // nothing to record
            }

            @Override
            public void rateLimited(boolean system, boolean search) {
                // nothing to record
            }

            @Override
            public void writeDenied(String reason) {
                // nothing to record
            }

            @Override
            public void archiveRun(boolean success) {
                // nothing to record
            }

            @Override
            public void archiveLag(String tenantId, boolean platform, long seconds) {
                // nothing to record
            }
        };
    }
}
