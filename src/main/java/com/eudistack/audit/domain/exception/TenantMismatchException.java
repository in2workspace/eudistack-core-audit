package com.eudistack.audit.domain.exception;

/**
 * The request tenant is not the caller's, or `tenant` was sent by someone who is not the platform administrator (`tenant_mismatch`).
 */
public final class TenantMismatchException extends AuditDomainException {

    public TenantMismatchException(String message) {
        super(message);
    }
}
