package com.eudistack.audit.domain.exception;

/**
 * An event_type of the query is not in the catalogue (`unknown_event_type`).
 */
public final class UnknownEventTypeException extends AuditDomainException {

    public UnknownEventTypeException(String message) {
        super(message);
    }
}
