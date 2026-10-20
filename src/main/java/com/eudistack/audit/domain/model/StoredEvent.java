package com.eudistack.audit.domain.model;

/**
 * An event read from the store, with the schema it was read from (needed to merge {@code tenant=all}).
 */
public record StoredEvent(AuditEvent event, String schema) {

    public CursorPosition position() {
        return new CursorPosition(event.occurredAt(), event.eventId(), schema);
    }
}
