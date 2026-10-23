package com.eudistack.audit.infrastructure.persistence.repository;

import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.TenantRef;
import com.eudistack.audit.domain.port.out.SelfAuditPort;
import com.eudistack.audit.infrastructure.persistence.tenant.JdbcPool;
import com.eudistack.audit.infrastructure.persistence.tenant.SearchPath;

/**
 * Stores an event of the API itself, committed in its own transaction, with the pool of the profile
 * that generates it: {@code AuditWriteDenied} with the writer, {@code AuditTrailQueried} with the reader
 * (a trigger keeps the reader to that type), {@code AuditEventsArchived} with the archiver.
 */
public final class SelfAuditJdbc implements SelfAuditPort {

    private final JdbcPool pool;
    private final EventSql sql;

    public SelfAuditJdbc(JdbcPool pool, EventSql sql) {
        this.pool = pool;
        this.sql = sql;
    }

    @Override
    public void storeEvent(TenantRef target, AuditEvent event) {
        pool.inTransaction(jdbc -> {
            SearchPath.setLocal(jdbc, target.schemaName());
            return sql.insertOwnEvent(jdbc, event);
        });
    }
}
