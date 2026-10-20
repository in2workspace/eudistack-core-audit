package com.eudistack.audit.domain.policy;

import com.eudistack.audit.domain.exception.BadRequestException;
import com.eudistack.audit.domain.exception.PeriodArchivedException;

import java.time.Duration;
import java.time.Instant;

/**
 * Period of a query (PRD §5 "Consulta", AD-6).
 *
 * <ul>
 *   <li>Without {@code to}, now. Without {@code from}, 7 days before {@code to}.</li>
 *   <li>At most 31 days, and {@code from} before {@code to}.</li>
 *   <li>Only the last 90 days are served, by {@code occurred_at}: an earlier {@code from} is
 *       {@code period_archived}.</li>
 * </ul>
 */
public final class QueryWindowPolicy {

    public static final Duration ONLINE_PERIOD = Duration.ofDays(90);
    public static final Duration MAX_PERIOD = Duration.ofDays(31);
    public static final Duration DEFAULT_PERIOD = Duration.ofDays(7);

    private QueryWindowPolicy() {
    }

    public record Window(Instant from, Instant to) {
    }

    public static Window resolve(Instant requestedFrom, Instant requestedTo, Instant now) {
        Instant to = requestedTo != null ? requestedTo : now;
        Instant from = requestedFrom != null ? requestedFrom : to.minus(DEFAULT_PERIOD);
        if (!from.isBefore(to)) {
            throw new BadRequestException("from", "from must be before to");
        }
        if (Duration.between(from, to).compareTo(MAX_PERIOD) > 0) {
            throw new BadRequestException("from", "The period must not exceed 31 days");
        }
        Instant oldestOnline = oldestOnline(now);
        if (from.isBefore(oldestOnline)) {
            throw new PeriodArchivedException(oldestOnline);
        }
        return new Window(from, to);
    }

    public static Instant oldestOnline(Instant now) {
        return now.minus(ONLINE_PERIOD);
    }

    public static boolean isOnline(Instant occurredAt, Instant now) {
        return !occurredAt.isBefore(oldestOnline(now));
    }
}
