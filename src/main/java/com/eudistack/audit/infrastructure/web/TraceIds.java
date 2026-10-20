package com.eudistack.audit.infrastructure.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;

import java.util.UUID;

/**
 * Trace identifier of the current request: the {@code correlation_id} of the events the API generates
 * (design §3.4.8). Falls back to a random identifier when tracing is off.
 */
public final class TraceIds {

    private final ObjectProvider<Tracer> tracer;

    public TraceIds(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    public String current() {
        Tracer current = tracer.getIfAvailable();
        if (current != null) {
            Span span = current.currentSpan();
            if (span != null && span.context().traceId() != null && !span.context().traceId().isBlank()) {
                return span.context().traceId();
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
