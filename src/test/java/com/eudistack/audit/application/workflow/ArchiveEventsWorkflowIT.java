package com.eudistack.audit.application.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariDataSource;
import com.eudistack.audit.domain.model.ActorKind;
import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.model.Power;
import com.eudistack.audit.domain.model.TenantRef;
import com.eudistack.audit.domain.policy.SelfEventFactory;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;
import com.eudistack.audit.domain.port.out.ColdStoragePort;
import com.eudistack.audit.infrastructure.persistence.repository.ArchiveJdbc;
import com.eudistack.audit.infrastructure.persistence.repository.EventSql;
import com.eudistack.audit.infrastructure.persistence.repository.SelfAuditJdbc;
import com.eudistack.audit.infrastructure.persistence.repository.TenantDirectoryJdbc;
import com.eudistack.audit.infrastructure.persistence.tenant.JdbcPool;
import com.eudistack.audit.support.AuditDatabase;
import com.eudistack.audit.support.InMemoryColdStorage;
import com.eudistack.audit.support.TestEvents;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC-20, AC-21 and AC-22 on the real database, as {@code audit_archiver}, with a cold storage that has
 * the guarantees of S3 Object Lock in compliance mode.
 */
class ArchiveEventsWorkflowIT {

    private static final TenantRef RED = new TenantRef("red-logistica", "red_logistica");
    private static HikariDataSource archiverDataSource;

    private final MutableClock storageClock = new MutableClock(Instant.now());
    private InMemoryColdStorage storage;
    private ArchiveEventsWorkflow workflow;
    private RecoverArchiveWorkflow recovery;
    private EventSql sql;

    @BeforeAll
    static void pool() {
        archiverDataSource = AuditDatabase.pool("it-archiver", "audit_archiver", "archiver");
    }

    @AfterAll
    static void close() {
        archiverDataSource.close();
    }

    @BeforeEach
    void setUp() {
        sql = new EventSql(TestEvents.MAPPER);
        JdbcPool pool = new JdbcPool(archiverDataSource);
        TenantDirectoryJdbc directory = new TenantDirectoryJdbc(pool);
        storage = new InMemoryColdStorage(storageClock);
        Clock clock = Clock.systemUTC();
        SelfEventFactory selfEvents = new SelfEventFactory(clock, "2.2.0");
        workflow = new ArchiveEventsWorkflow(directory, new ArchiveJdbc(pool, sql), storage, selfEvents,
                AuditTelemetryPort.noop(), clock);
        recovery = new RecoverArchiveWorkflow(directory, storage, workflow, new SelfAuditJdbc(pool, sql), selfEvents);
    }

    /**
     * Plants an event as if it had been recorded some days ago (only possible outside the API).
     */
    private static AuditEvent plant(String correlation, Duration ago) {
        ObjectNode json = TestEvents.issuanceCompleted("red-logistica", correlation, "ARCH");
        Instant recordedAt = Instant.now().minus(ago).truncatedTo(ChronoUnit.MICROS);
        json.put("occurred_at", recordedAt.minusSeconds(5).toString());
        AuditDatabase.superuser().sql("""
                        INSERT INTO red_logistica.audit_event (event_id, event_type, event_version, occurred_at,
                          recorded_at, tenant, organization, source, actor, action, resource, result, correlation_id,
                          details, payload_hash)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, CAST(? AS jsonb), '\\x00')
                        """)
                .param(UUID.fromString(json.get("event_id").asText()))
                .param("IssuanceCompleted").param("2.2.0")
                .param(OffsetDateTime.parse(json.get("occurred_at").asText()))
                .param(OffsetDateTime.ofInstant(recordedAt, ZoneOffset.UTC))
                .param("red-logistica").param("VATES-B00000000").param("issuer")
                .param(json.get("actor").toString()).param("Issue").param(json.get("resource").asText())
                .param("Success").param(correlation).param(json.get("details").toString())
                .update();
        return AuditEvent.fromValidatedJson(json).withRecordedAt(recordedAt).withTenant("red-logistica");
    }

    private static List<String> idsInDatabase(String correlation) {
        return AuditDatabase.superuser().sql("SELECT event_id::text FROM red_logistica.audit_event WHERE correlation_id = ?")
                .param(correlation).query(String.class).list();
    }

    private static List<String> archivedRecords(String day) {
        return AuditDatabase.superuser().sql("""
                        SELECT result FROM platform.audit_event WHERE event_type = 'AuditEventsArchived'
                        AND details->>'archived_tenant' = 'red-logistica' AND details->>'period_from' LIKE ?
                        """)
                .param(day + "%").query(String.class).list();
    }

    @Test
    void ac20_eventsOf91DaysGoToColdStorage_eventsOf89DaysStay() {
        String correlation = "arch-" + UUID.randomUUID();
        AuditEvent old = plant(correlation, Duration.ofDays(91));
        AuditEvent recent = plant(correlation, Duration.ofDays(89));

        workflow.run();

        assertThat(idsInDatabase(correlation)).containsExactly(recent.eventId().toString());
        LocalDate day = LocalDate.ofInstant(old.recordedAt(), ZoneOffset.UTC);
        ArchiveFormat.Manifest manifest = ArchiveFormat.manifestFromBytes(
                storage.get(ArchiveFormat.manifestKey("red-logistica", day, 0)).orElseThrow());
        assertThat(manifest.eventIds()).contains(old.eventId());
        assertThat(manifest.eventCount()).isEqualTo(manifest.eventIds().size());
        assertThat(archivedRecords(day.toString())).contains("Success");
    }

    @Test
    void ac20_copyFails_noEventLeavesTheDatabase() {
        String correlation = "arch-" + UUID.randomUUID();
        AuditEvent old = plant(correlation, Duration.ofDays(95));
        storage.unavailable(true);

        workflow.run();

        assertThat(idsInDatabase(correlation)).containsExactly(old.eventId().toString());
        assertThat(archivedRecords(LocalDate.ofInstant(old.recordedAt(), ZoneOffset.UTC).toString())).contains("Failure");
    }

    @Test
    void ac21_archivedObject_cannotBeDeletedBeforeSevenYears_thenExpires() {
        String correlation = "arch-" + UUID.randomUUID();
        AuditEvent old = plant(correlation, Duration.ofDays(97));
        workflow.run();
        String key = ArchiveFormat.eventsKey("red-logistica", LocalDate.ofInstant(old.recordedAt(), ZoneOffset.UTC), 0);

        assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(ColdStoragePort.ColdStorageException.class);
        assertThat(storage.lockedUntil(key).orElseThrow())
                .isAfterOrEqualTo(old.recordedAt().atZone(ZoneOffset.UTC).plusYears(7).toInstant());

        storageClock.set(old.recordedAt().atZone(ZoneOffset.UTC).plusYears(7).plusDays(2).toInstant());
        storage.expire();

        assertThat(storage.get(key)).isEmpty();
    }

    @Test
    void ac22_recovery_deliversExactlyWhatWasStored_andLeavesAuditTrailQueried() {
        String correlation = "arch-" + UUID.randomUUID();
        AuditEvent first = plant(correlation, Duration.ofDays(99));
        AuditEvent second = plant(correlation, Duration.ofDays(99).minusMinutes(1));
        workflow.run();
        LocalDate day = LocalDate.ofInstant(first.recordedAt(), ZoneOffset.UTC);
        Caller admin = new Caller("platform-admin", ActorKind.PERSON, "Admin", null, "VATES-EUDISTACK", "platform",
                null, List.of(new Power("organization", "EUDISTACK", "System", List.of("Administration"))));
        String trace = "recover-" + UUID.randomUUID();

        List<AuditEvent> recovered = recovery.recover(admin, "red-logistica", day.minusDays(1), day.plusDays(1), trace);

        assertThat(recovered.stream().filter(e -> e.correlationId().equals(correlation)).toList())
                .containsExactlyInAnyOrder(first, second);
        assertThat(AuditDatabase.superuser().sql("SELECT count(*) FROM red_logistica.audit_event "
                        + "WHERE event_type = 'AuditTrailQueried' AND correlation_id = ?")
                .param(trace).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void ac22_recovery_requiresTheAdministrator() {
        Caller auditor = new Caller("auditor", ActorKind.PERSON, null, null, "VATES-B1", "red-logistica", null,
                List.of(new Power("organization", "RED-LOGISTICA", "Audit", List.of("Read"))));

        assertThatThrownBy(() -> recovery.recover(auditor, "red-logistica", LocalDate.now(), LocalDate.now(), "t"))
                .isInstanceOf(com.eudistack.audit.domain.exception.InsufficientPermissionException.class);
    }

    @Test
    void secondRun_doesNotDuplicateNorRewrite() {
        String correlation = "arch-" + UUID.randomUUID();
        plant(correlation, Duration.ofDays(93));
        workflow.run();
        List<String> keys = storage.keys();

        workflow.run();

        assertThat(storage.keys()).containsExactlyElementsOf(keys);
        assertThat(idsInDatabase(correlation)).isEmpty();
    }

    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant value) {
            this.now = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
