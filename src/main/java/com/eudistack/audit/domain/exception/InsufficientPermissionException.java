package com.eudistack.audit.domain.exception;

/**
 * The caller lacks the power of the operation (`insufficient_permission`).
 */
public final class InsufficientPermissionException extends AuditDomainException {

    public InsufficientPermissionException(String message) {
        super(message);
    }
}
