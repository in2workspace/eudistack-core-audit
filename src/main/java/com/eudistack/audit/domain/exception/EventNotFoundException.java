package com.eudistack.audit.domain.exception;

/**
 * The event does not exist, is outside the caller's scope or is archived (`event_not_found`). All cases look the same.
 */
public final class EventNotFoundException extends AuditDomainException {

    public EventNotFoundException(String message) {
        super(message);
    }
}
