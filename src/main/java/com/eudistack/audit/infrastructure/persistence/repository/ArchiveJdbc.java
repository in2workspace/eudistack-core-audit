package com.eudistack.audit.infrastructure.persistence.repository;

import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.TenantRef;
import com.eudistack.audit.domain.port.out.ArchiveRepositoryPort;
import com.eudistack.audit.infrastructure.persistence.tenant.JdbcPool;
import com.eudistack.audit.infrastructure.persistence.tenant.SearchPath;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Store operations of the {@code archiver} profile ({@code audit_archiver}). The archiver is the only
 * role allowed to delete, and only by the identifiers of a verified manifest.
 */
public final class ArchiveJdbc implements ArchiveRepositoryPort {

    private static final int DELETE_CHUNK = 1000;

    private final JdbcPool pool;
    private final EventSql sql;

    public ArchiveJdbc(JdbcPool pool, EventSql sql) {
        this.pool = pool;
        this.sql = sql;
    }

    @Override
    public List<LocalDate> daysWithEventsBefore(TenantRef tenant, Instant cutoff) {
        return pool.inTransaction(jdbc -> {
            SearchPath.setLocal(jdbc, tenant.schemaName());
            return jdbc.sql("""
                            SELECT DISTINCT (recorded_at AT TIME ZONE 'UTC')::date AS day
                            FROM audit_event WHERE recorded_at < :cutoff ORDER BY day
                            """)
                    .param("cutoff", utc(cutoff))
                    .query((rs, row) -> rs.getObject("day", LocalDate.class))
                    .list();
        });
    }

    @Override
    public List<AuditEvent> eventsRecordedOn(TenantRef tenant, LocalDate day) {
        return pool.inTransaction(jdbc -> {
            SearchPath.setLocal(jdbc, tenant.schemaName());
            return jdbc.sql("SELECT " + EventSql.COLUMNS + " FROM audit_event"
                            + " WHERE recorded_at >= :from AND recorded_at < :to ORDER BY occurred_at, event_id")
                    .param("from", utc(day.atStartOfDay(ZoneOffset.UTC).toInstant()))
                    .param("to", utc(day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()))
                    .query(sql.rowMapper())
                    .list();
        });
    }

    @Override
    public Optional<Instant> oldestRecordedAt(TenantRef tenant) {
        return pool.inTransaction(jdbc -> {
            SearchPath.setLocal(jdbc, tenant.schemaName());
            List<OffsetDateTime> oldest = jdbc.sql("SELECT min(recorded_at) AS oldest FROM audit_event")
                    .query((rs, row) -> rs.getObject("oldest", OffsetDateTime.class))
                    .list();
            return oldest.isEmpty() ? Optional.<Instant>empty()
                    : Optional.ofNullable(oldest.getFirst()).map(OffsetDateTime::toInstant);
        });
    }

    @Override
    public boolean tryLock(String tenantId, LocalDate day, String owner, Instant staleBefore) {
        return pool.inTransaction(jdbc -> jdbc.sql("""
                        INSERT INTO platform.archive_lock (tenant_id, day, locked_by)
                        VALUES (:tenant_id, :day, :owner)
                        ON CONFLICT (tenant_id, day) DO UPDATE
                          SET locked_by = EXCLUDED.locked_by, locked_at = clock_timestamp()
                          WHERE platform.archive_lock.locked_at < :stale_before
                        RETURNING locked_by
                        """)
                .param("tenant_id", tenantId)
                .param("day", day)
                .param("owner", owner)
                .param("stale_before", utc(staleBefore))
                .query(String.class)
                .optional()
                .isPresent());
    }

    @Override
    public void unlock(String tenantId, LocalDate day, String owner) {
        pool.inTransaction(jdbc -> jdbc.sql(
                        "DELETE FROM platform.archive_lock WHERE tenant_id = :tenant_id AND day = :day AND locked_by = :owner")
                .param("tenant_id", tenantId)
                .param("day", day)
                .param("owner", owner)
                .update());
    }

    @Override
    public void deleteArchived(TenantRef tenant, Collection<UUID> eventIds, AuditEvent archivedRecord) {
        pool.inTransaction(jdbc -> {
            SearchPath.setLocal(jdbc, tenant.schemaName());
            List<UUID> ids = new ArrayList<>(eventIds);
            for (int from = 0; from < ids.size(); from += DELETE_CHUNK) {
                jdbc.sql("DELETE FROM audit_event WHERE event_id IN (:ids)")
                        .param("ids", ids.subList(from, Math.min(ids.size(), from + DELETE_CHUNK)))
                        .update();
            }
            SearchPath.setLocal(jdbc, TenantRef.PLATFORM.schemaName());
            return sql.insertOwnEvent(jdbc, archivedRecord);
        });
    }

    @Override
    public void recordOutcome(AuditEvent archivedRecord) {
        pool.inTransaction(jdbc -> {
            SearchPath.setLocal(jdbc, TenantRef.PLATFORM.schemaName());
            return sql.insertOwnEvent(jdbc, archivedRecord);
        });
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
