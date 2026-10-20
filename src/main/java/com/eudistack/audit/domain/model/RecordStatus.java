package com.eudistack.audit.domain.model;

/**
 * Per-event result of {@code POST /api/v1/events}.
 */
public enum RecordStatus {
    ACCEPTED("accepted"),
    DUPLICATE("duplicate"),
    REJECTED("rejected");

    private final String wireValue;

    RecordStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
