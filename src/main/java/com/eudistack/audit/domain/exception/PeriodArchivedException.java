package com.eudistack.audit.domain.exception;

import java.time.Instant;

/**
 * Part of the requested period is older than the 90-day online period ({@code period_archived}).
 */
public final class PeriodArchivedException extends AuditDomainException {

    private final Instant oldestOnline;

    public PeriodArchivedException(Instant oldestOnline) {
        super("The period starts before the online period. Oldest instant still online: " + oldestOnline);
        this.oldestOnline = oldestOnline;
    }

    public Instant oldestOnline() {
        return oldestOnline;
    }
}
