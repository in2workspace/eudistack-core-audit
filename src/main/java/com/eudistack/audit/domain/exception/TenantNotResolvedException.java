package com.eudistack.audit.domain.exception;

/**
 * No tenant could be resolved for the request (`tenant_not_resolved`).
 */
public final class TenantNotResolvedException extends AuditDomainException {

    public TenantNotResolvedException(String message) {
        super(message);
    }
}
