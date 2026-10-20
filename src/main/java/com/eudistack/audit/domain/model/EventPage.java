package com.eudistack.audit.domain.model;

import java.util.List;

/**
 * One page of a listing. {@code nextCursor} is {@code null} on the last page.
 */
public record EventPage(List<AuditEvent> events, String nextCursor) {

    public EventPage {
        events = List.copyOf(events);
    }
}
