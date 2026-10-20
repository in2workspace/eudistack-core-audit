package com.eudistack.audit.domain.port.in;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Query parameters of {@code GET /api/v1/events} as received, before validation.
 *
 * @param receivedFilters the parameters as received, recorded in {@code AuditTrailQueried.details.filters}
 */
public record ListRequest(
        String from,
        String to,
        List<String> eventTypes,
        List<String> results,
        String correlationId,
        String resource,
        String actorId,
        String detailsPath,
        String detailsValue,
        String organization,
        String tenant,
        String limit,
        String cursor,
        ObjectNode receivedFilters
) {

    public ListRequest {
        eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
        results = results == null ? List.of() : List.copyOf(results);
    }

    public boolean isPathSearch() {
        return detailsPath != null;
    }
}
