package com.eudistack.audit.domain.model;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A validated listing query, ready for the store. Every filter is optional except the period.
 *
 * @param from          start of the period, {@code occurred_at} inclusive
 * @param to            end of the period, {@code occurred_at} exclusive
 * @param eventTypes    event types, empty for all
 * @param results       results, empty for all
 * @param containment   {@code details @>} JSON built from an allow-listed searchable path, or null
 * @param limit         page size
 * @param after         position after which the page starts, or null for the first page
 */
public record QuerySpec(
        Instant from,
        Instant to,
        List<String> eventTypes,
        List<Result> results,
        String correlationId,
        String resource,
        String actorId,
        String organization,
        ObjectNode containment,
        int limit,
        CursorPosition after
) {

    public QuerySpec {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
        results = results == null ? List.of() : List.copyOf(results);
    }
}
