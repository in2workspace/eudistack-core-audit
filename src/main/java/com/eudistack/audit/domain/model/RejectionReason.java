package com.eudistack.audit.domain.model;

/**
 * Why an event of a batch was rejected. Each value carries the error {@code type} of the contract
 * and the {@code reason} of the {@code AuditEventRejected} it leaves in {@code platform}.
 */
public enum RejectionReason {
    UNKNOWN_EVENT_TYPE("unknown_event_type", "UnknownEventType", "The event type or version is not in the catalogue"),
    INVALID_EVENT("invalid_event", "InvalidEvent", "The event does not match its type"),
    UNKNOWN_TENANT("unknown_tenant", "UnknownTenant", "The tenant does not exist"),
    EVENT_ID_CONFLICT("event_id_conflict", "EventIdConflict", "The event_id is already stored with different content");

    private final String errorType;
    private final String catalogueReason;
    private final String title;

    RejectionReason(String errorType, String catalogueReason, String title) {
        this.errorType = errorType;
        this.catalogueReason = catalogueReason;
        this.title = title;
    }

    public String errorType() {
        return errorType;
    }

    public String catalogueReason() {
        return catalogueReason;
    }

    public String title() {
        return title;
    }
}
