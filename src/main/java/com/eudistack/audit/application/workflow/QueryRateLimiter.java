package com.eudistack.audit.application.workflow;

import com.eudistack.audit.domain.exception.RateLimitedException;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;
import com.eudistack.audit.domain.port.out.RateLimiterPort;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Query volume limit (FR-18, PRD §5): per minute, 60 queries per organization for systems and 30 per
 * person; of those, path searches ({@code details_path}) 20 and 10, so the census of persons cannot be
 * walked. Support can raise the limits of an organization. A 429 is not recorded as
 * {@code AuditTrailQueried} (AD-8): it only shows in {@code audit.ratelimit.rejected}.
 */
public final class QueryRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    public record Limits(int system, int person, int systemSearch, int personSearch) {
    }

    private final RateLimiterPort counters;
    private final Clock clock;
    private final Limits defaults;
    private final Map<String, Limits> overrides;
    private final AuditTelemetryPort telemetry;

    public QueryRateLimiter(RateLimiterPort counters, Clock clock, Limits defaults, Map<String, Limits> overrides,
                            AuditTelemetryPort telemetry) {
        this.counters = counters;
        this.clock = clock;
        this.defaults = defaults;
        this.overrides = Map.copyOf(overrides);
        this.telemetry = telemetry;
    }

    public void check(Caller caller, boolean pathSearch) {
        Instant now = clock.instant();
        Instant window = now.truncatedTo(ChronoUnit.MINUTES);
        long retryAfter = Math.max(1, Duration.between(now, window.plus(WINDOW)).toSeconds());
        Limits limits = caller.organization() == null ? defaults : overrides.getOrDefault(caller.organization(), defaults);
        boolean system = caller.isSystem();
        String actor = caller.organization() != null ? caller.organization() : caller.subject();
        String key = system ? "system:" + actor : "person:" + caller.subject();
        if (counters.hit(key, window) > (system ? limits.system() : limits.person())) {
            telemetry.rateLimited(system, false);
            throw new RateLimitedException(retryAfter);
        }
        if (pathSearch && counters.hit(key + ":search", window) > (system ? limits.systemSearch() : limits.personSearch())) {
            telemetry.rateLimited(system, true);
            throw new RateLimitedException(retryAfter);
        }
    }
}
