package com.eudistack.audit.domain.exception;

/**
 * The query volume limit was exceeded ({@code rate_limited}).
 */
public final class RateLimitedException extends AuditDomainException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super("The query volume limit was exceeded");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
