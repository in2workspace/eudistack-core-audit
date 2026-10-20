package com.eudistack.audit.domain.port.out;

import java.time.Instant;

/**
 * Fixed-window counters of the query volume limit (FR-18), shared by every reader replica.
 */
public interface RateLimiterPort {

    /**
     * Counts one more hit on the bucket for the window and returns the hits so far, this one included.
     */
    long hit(String bucketKey, Instant windowStart);
}
