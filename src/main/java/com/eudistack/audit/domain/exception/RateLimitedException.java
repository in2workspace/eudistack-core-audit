package com.eudistack.audit.domain.exception;

/**
 * The query volume limit was exceeded ({@code rate_limited}).
 */
public final class RateLimitedException extends AuditDomainException {

    private final long retryAfterSeconds;
    private final boolean system;
    private final boolean search;

    public RateLimitedException(long retryAfterSeconds, boolean system, boolean search) {
        super("The query volume limit was exceeded");
        this.retryAfterSeconds = retryAfterSeconds;
        this.system = system;
        this.search = search;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** A system caller, as opposed to a person. Low cardinality: never the subject. */
    public boolean system() {
        return system;
    }

    /** The call also counted against the path-search limit. */
    public boolean search() {
        return search;
    }
}
