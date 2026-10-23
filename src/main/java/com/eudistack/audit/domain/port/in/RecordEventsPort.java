package com.eudistack.audit.domain.port.in;

import com.fasterxml.jackson.databind.JsonNode;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.model.EventOutcome;

import java.util.List;

/**
 * Write face of the audit API: {@code POST /api/v1/events}.
 */
public interface RecordEventsPort {

    /**
     * Validates and stores a batch. A 200 means every outcome is already durable.
     *
     * @param caller  authenticated caller
     * @param body    parsed request body, or {@code null} when it was not JSON
     * @param traceId trace identifier of the request
     */
    List<EventOutcome> recordBatch(Caller caller, JsonNode body, String traceId);

    /**
     * Leaves an {@code AuditWriteDenied} ({@code InvalidToken}) for a write whose token was missing or
     * invalid (FR-11). Best effort: when the store is down the denial is only logged.
     */
    void recordAuthenticationFailure(int eventCount, String traceId);
}
