package com.eudistack.audit.domain.exception;

/**
 * The access token is not valid, has expired or is not meant for this service (`invalid_token`).
 */
public final class InvalidTokenException extends AuditDomainException {

    public InvalidTokenException(String message) {
        super(message);
    }
}
