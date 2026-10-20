package com.eudistack.audit.domain.exception;

/**
 * The audit store, the connection pool or the Verifier JWKS is not available ({@code service_unavailable}).
 * Nothing of the batch is stored; the producer retries the whole batch after {@code Retry-After}.
 */
public final class ServiceUnavailableException extends AuditDomainException {

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
