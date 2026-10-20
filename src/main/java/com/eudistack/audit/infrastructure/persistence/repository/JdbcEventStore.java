package com.eudistack.audit.infrastructure.persistence.repository;

import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.TenantRef;
import com.eudistack.audit.domain.policy.IdempotencyPolicy;
import com.eudistack.audit.domain.port.out.AuditEventRepositoryPort;
import com.eudistack.audit.domain.port.out.BatchWriter;
import com.eudistack.audit.domain.port.out.InsertOutcome;
import com.eudistack.audit.infrastructure.persistence.tenant.JdbcPool;
import com.eudistack.audit.infrastructure.persistence.tenant.SearchPath;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.function.Function;

/**
 * Batch writer of the {@code writer} profile ({@code audit_writer}, AD-1, AD-2, AD-4).
 *
 * <p>One transaction per batch. Before each statement the {@code search_path} is set locally to the
 * schema of the event (only when it changes). {@code INSERT … ON CONFLICT (event_id) DO NOTHING}:
 * under READ COMMITTED a concurrent insert of the same {@code event_id} waits for the other transaction
 * and then does nothing, so the loser rereads the committed hash and answers {@code duplicate} or
 * {@code event_id_conflict} without aborting the batch (design §3.4.7). The writer can only read
 * {@code event_id} and {@code payload_hash}.
 */
public final class JdbcEventStore implements AuditEventRepositoryPort {

    private final JdbcPool pool;
    private final EventSql sql;

    public JdbcEventStore(JdbcPool pool, EventSql sql) {
        this.pool = pool;
        this.sql = sql;
    }

    @Override
    public <T> T writeBatch(Function<BatchWriter, T> work) {
        return pool.inTransaction(jdbc -> work.apply(new TransactionalWriter(jdbc)));
    }

    private final class TransactionalWriter implements BatchWriter {

        private final JdbcClient jdbc;
        private String currentSchema;

        TransactionalWriter(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public InsertOutcome insertIfAbsent(TenantRef tenant, AuditEvent event, byte[] payloadHash) {
            useSchema(tenant.schemaName());
            if (sql.insertIfAbsent(jdbc, event, payloadHash) == 1) {
                return InsertOutcome.INSERTED;
            }
            byte[] stored = jdbc.sql("SELECT payload_hash FROM audit_event WHERE event_id = :event_id")
                    .param("event_id", event.eventId())
                    .query(byte[].class)
                    .single();
            return IdempotencyPolicy.sameContent(stored, payloadHash)
                    ? InsertOutcome.SAME_CONTENT : InsertOutcome.DIFFERENT_CONTENT;
        }

        @Override
        public void insertPlatformEvent(AuditEvent event) {
            useSchema(TenantRef.PLATFORM.schemaName());
            sql.insertOwnEvent(jdbc, event);
        }

        private void useSchema(String schema) {
            if (!schema.equals(currentSchema)) {
                SearchPath.setLocal(jdbc, schema);
                currentSchema = schema;
            }
        }
    }
}
