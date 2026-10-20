package com.eudistack.audit.domain.model;

import java.util.List;

/**
 * Result of one event of a batch, in the order received.
 *
 * @param eventId    {@code event_id} as received (empty when the event did not carry a readable one)
 * @param status     accepted, duplicate or rejected
 * @param reason     only with {@link RecordStatus#REJECTED}
 * @param violations only with {@link RejectionReason#INVALID_EVENT}
 */
public record EventOutcome(String eventId, RecordStatus status, RejectionReason reason, List<Violation> violations) {

    public EventOutcome {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public static EventOutcome accepted(String eventId) {
        return new EventOutcome(eventId, RecordStatus.ACCEPTED, null, List.of());
    }

    public static EventOutcome duplicate(String eventId) {
        return new EventOutcome(eventId, RecordStatus.DUPLICATE, null, List.of());
    }

    public static EventOutcome rejected(String eventId, RejectionReason reason, List<Violation> violations) {
        return new EventOutcome(eventId, RecordStatus.REJECTED, reason, violations);
    }
}
