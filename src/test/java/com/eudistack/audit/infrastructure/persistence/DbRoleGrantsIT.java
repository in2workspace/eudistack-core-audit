package com.eudistack.audit.infrastructure.persistence;

import com.zaxxer.hikari.HikariDataSource;
import com.eudistack.audit.support.AuditDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AD-10 and design §3.4.2 / §3.8, checked against the real grants and triggers: nobody updates an
 * event, only the archiver deletes, the writer cannot read event content, the reader only inserts its
 * own {@code AuditTrailQueried}.
 */
class DbRoleGrantsIT {

    private static HikariDataSource writer;
    private static HikariDataSource reader;
    private static HikariDataSource archiver;
    private static HikariDataSource migrator;

    @BeforeAll
    static void pools() {
        writer = AuditDatabase.pool("g-writer", "audit_writer", "writer");
        reader = AuditDatabase.pool("g-reader", "audit_reader", "reader");
        archiver = AuditDatabase.pool("g-archiver", "audit_archiver", "archiver");
        migrator = AuditDatabase.pool("g-migrator", "audit_migrator", "migrator");
    }

    @AfterAll
    static void close() {
        writer.close();
        reader.close();
        archiver.close();
        migrator.close();
    }

    private static UUID insert(JdbcClient jdbc, String schema, String eventType) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO \"" + schema + "\".audit_event (event_id, event_type, event_version, occurred_at, tenant,"
                        + " source, actor, action, resource, result, correlation_id, details, payload_hash) VALUES"
                        + " (?, ?, '2.2.0', now(), 'sandbox', 'test', '{\"kind\":\"System\",\"id\":\"t\"}', 'A', 'r:1',"
                        + " 'Success', 'c', '{}', '\\x00')")
                .param(id).param(eventType).update();
        return id;
    }

    @Test
    void writer_insertsButCannotReadContent() {
        JdbcClient jdbc = JdbcClient.create(writer);
        UUID id = insert(jdbc, "sandbox", "IssuanceCompleted");

        assertThat(jdbc.sql("SELECT payload_hash FROM sandbox.audit_event WHERE event_id = ?").param(id)
                .query(byte[].class).single()).isNotNull();
        assertThatThrownBy(() -> jdbc.sql("SELECT details FROM sandbox.audit_event WHERE event_id = ?").param(id)
                .query(String.class).single()).rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void nobody_updatesAnEvent_notEvenTheOwner() {
        UUID id = insert(JdbcClient.create(writer), "sandbox", "IssuanceCompleted");

        for (HikariDataSource pool : new HikariDataSource[] {writer, reader, archiver, migrator}) {
            assertThatThrownBy(() -> JdbcClient.create(pool)
                    .sql("UPDATE sandbox.audit_event SET resource = 'x' WHERE event_id = ?").param(id).update())
                    .as(pool.getUsername())
                    .rootCause().hasMessageMatching("(?s).*(permission denied|append-only).*");
        }
    }

    @Test
    void onlyTheArchiver_deletes() {
        UUID id = insert(JdbcClient.create(writer), "sandbox", "IssuanceCompleted");

        for (HikariDataSource pool : new HikariDataSource[] {writer, reader, migrator}) {
            assertThatThrownBy(() -> JdbcClient.create(pool)
                    .sql("DELETE FROM sandbox.audit_event WHERE event_id = ?").param(id).update())
                    .as(pool.getUsername())
                    .rootCause().hasMessageMatching("(?s).*(permission denied|only the archiver).*");
        }
        assertThat(JdbcClient.create(archiver).sql("DELETE FROM sandbox.audit_event WHERE event_id = ?").param(id)
                .update()).isEqualTo(1);
    }

    @Test
    void reader_insertsOnlyAuditTrailQueried() {
        JdbcClient jdbc = JdbcClient.create(reader);

        assertThat(insert(jdbc, "sandbox", "AuditTrailQueried")).isNotNull();
        assertThatThrownBy(() -> insert(jdbc, "sandbox", "IssuanceCompleted"))
                .rootCause().hasMessageContaining("the reader only inserts AuditTrailQueried");
    }

    @Test
    void truncate_isRefused() {
        assertThatThrownBy(() -> JdbcClient.create(migrator).sql("TRUNCATE sandbox.audit_event").update())
                .rootCause().hasMessageContaining("append-only");
    }

    @Test
    void runtimeRoles_cannotCreateSchemas() {
        assertThatThrownBy(() -> JdbcClient.create(writer).sql("CREATE SCHEMA intruder").update())
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void connectionPool_doesNotCarryASearchPath() {
        String searchPath = JdbcClient.create(reader).sql("SHOW search_path").query(String.class).single();

        assertThat(searchPath).doesNotContain("sandbox").doesNotContain("red_logistica");
    }
}
