package com.eudistack.audit.infrastructure.persistence.repository;

import com.eudistack.audit.domain.exception.ServiceUnavailableException;
import com.eudistack.audit.domain.port.out.RateLimiterPort;
import com.eudistack.audit.infrastructure.persistence.tenant.JdbcPool;
import org.springframework.dao.DataAccessException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Counters of FR-18 in {@code platform.rate_window}, shared by every reader replica. One atomic upsert
 * per hit; expired windows are removed by {@link #purgeBefore}.
 */
public final class RateLimiterJdbc implements RateLimiterPort {

    private final JdbcPool pool;

    public RateLimiterJdbc(JdbcPool pool) {
        this.pool = pool;
    }

    @Override
    public long hit(String bucketKey, Instant windowStart) {
        try {
            return pool.jdbc()
                    .sql("""
                            INSERT INTO platform.rate_window (bucket_key, window_start, hit_count)
                            VALUES (:bucket_key, :window_start, 1)
                            ON CONFLICT (bucket_key, window_start)
                            DO UPDATE SET hit_count = platform.rate_window.hit_count + 1
                            RETURNING hit_count
                            """)
                    .param("bucket_key", bucketKey)
                    .param("window_start", OffsetDateTime.ofInstant(windowStart, ZoneOffset.UTC))
                    .query(Long.class)
                    .single();
        } catch (DataAccessException e) {
            throw new ServiceUnavailableException("The rate limiter is not available", e);
        }
    }

    public int purgeBefore(Instant before) {
        return pool.jdbc()
                .sql("DELETE FROM platform.rate_window WHERE window_start < :before")
                .param("before", OffsetDateTime.ofInstant(before, ZoneOffset.UTC))
                .update();
    }
}
