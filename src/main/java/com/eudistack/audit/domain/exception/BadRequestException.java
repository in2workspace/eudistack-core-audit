package com.eudistack.audit.domain.exception;

import com.eudistack.audit.domain.model.Violation;

import java.util.List;

/**
 * The request or the batch is not valid ({@code BAD_REQUEST}). Nothing is stored.
 */
public final class BadRequestException extends AuditDomainException {

    private final transient List<Violation> violations;

    public BadRequestException(String message, List<Violation> violations) {
        super(message);
        this.violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public BadRequestException(String field, String message) {
        this(message, List.of(new Violation(field, message)));
    }

    public List<Violation> violations() {
        return violations;
    }
}
