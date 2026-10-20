package com.eudistack.audit.domain.port.out;

/**
 * Result of inserting an event whose {@code event_id} may already exist in its schema.
 */
public enum InsertOutcome {
    INSERTED,
    /** Already stored with the same payload hash: {@code duplicate}. */
    SAME_CONTENT,
    /** Already stored with another payload hash: {@code event_id_conflict}. */
    DIFFERENT_CONTENT
}
