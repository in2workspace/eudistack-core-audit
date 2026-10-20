package com.eudistack.audit.domain.exception;

/**
 * No access token was presented (`authentication_error`).
 */
public final class AuthenticationRequiredException extends AuditDomainException {

    public AuthenticationRequiredException(String message) {
        super(message);
    }
}
