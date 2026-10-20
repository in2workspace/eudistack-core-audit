package com.eudistack.audit.domain.exception;

/**
 * Root of the audit bounded context exceptions. The domain never knows the HTTP status: the web
 * adapter ({@code AuditExceptionHandler}) maps each subtype to its {@code problem+json}.
 */
public abstract sealed class AuditDomainException extends RuntimeException
        permits BadRequestException, UnknownEventTypeException, PathNotSearchableException,
        AuthenticationRequiredException, InvalidTokenException, InsufficientPermissionException,
        TenantMismatchException, TenantNotResolvedException, PeriodArchivedException,
        RateLimitedException, EventNotFoundException, ServiceUnavailableException {

    protected AuditDomainException(String message) {
        super(message);
    }

    protected AuditDomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
