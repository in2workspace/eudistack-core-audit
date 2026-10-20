package com.eudistack.audit.domain.port.out;

import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.TenantRef;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Store operations of the archiver ({@code audit_archiver}).
 */
public interface ArchiveRepositoryPort {

    /**
     * UTC days, before {@code cutoff}, that still have events by {@code recorded_at}.
     */
    List<LocalDate> daysWithEventsBefore(TenantRef tenant, Instant cutoff);

    /**
     * Events of one UTC day by {@code recorded_at}, in listing order.
     */
    List<AuditEvent> eventsRecordedOn(TenantRef tenant, LocalDate day);

    Optional<Instant> oldestRecordedAt(TenantRef tenant);

    /**
     * Takes the lock of a tenant and day; a lock taken before {@code staleBefore} is taken over.
     */
    boolean tryLock(String tenantId, LocalDate day, String owner, Instant staleBefore);

    void unlock(String tenantId, LocalDate day, String owner);

    /**
     * In one transaction: deletes the archived events by identifier, never by date, and stores the
     * {@code AuditEventsArchived} of the run in {@code platform}.
     */
    void deleteArchived(TenantRef tenant, Collection<UUID> eventIds, AuditEvent archivedRecord);

    /**
     * Stores an {@code AuditEventsArchived} in {@code platform} without deleting anything.
     */
    void recordOutcome(AuditEvent archivedRecord);
}
