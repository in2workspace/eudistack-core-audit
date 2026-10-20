package com.eudistack.audit.domain.model;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * Position of an event in the listing order: {@code occurred_at}, then {@code event_id}, then the
 * schema (only relevant for {@code tenant=all}, where two tenants may reuse an {@code event_id}).
 *
 * <p>{@code event_id} compares as PostgreSQL compares {@code uuid} (unsigned bytes), which is the
 * lexicographic order of the canonical lower-case text — not {@link UUID#compareTo}.
 */
public record CursorPosition(Instant occurredAt, UUID eventId, String schema) implements Comparable<CursorPosition> {

    private static final Comparator<CursorPosition> ORDER = Comparator
            .comparing(CursorPosition::occurredAt)
            .thenComparing(p -> p.eventId().toString())
            .thenComparing(p -> p.schema() == null ? "" : p.schema());

    public CursorPosition {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(eventId, "eventId");
    }

    @Override
    public int compareTo(CursorPosition other) {
        return ORDER.compare(this, other);
    }
}
