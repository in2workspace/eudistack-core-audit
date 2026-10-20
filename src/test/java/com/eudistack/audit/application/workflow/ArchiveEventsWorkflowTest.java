package com.eudistack.audit.application.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.TenantRef;
import com.eudistack.audit.domain.policy.SelfEventFactory;
import com.eudistack.audit.domain.port.out.ArchiveRepositoryPort;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;
import com.eudistack.audit.support.InMemoryColdStorage;
import com.eudistack.audit.support.InMemoryEventStore;
import com.eudistack.audit.support.TestEvents;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveEventsWorkflowTest {

    private static final Instant NOW = Instant.parse("2026-10-19T02:00:00Z");
    private static final TenantRef RED = TenantRef.forNewTenant("red-logistica");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryColdStorage storage = new InMemoryColdStorage(clock);
    private final FakeArchiveRepository repository = new FakeArchiveRepository();
    private final InMemoryEventStore directory = new InMemoryEventStore().withTenant("red-logistica");
    private final ArchiveEventsWorkflow workflow = new ArchiveEventsWorkflow(directory, repository, storage,
            new SelfEventFactory(clock, "2.2.0"), AuditTelemetryPort.noop(), clock);

    private AuditEvent recordedDaysAgo(int days) {
        ObjectNode json = TestEvents.issuanceCompleted("red-logistica", "c", "1Z");
        return AuditEvent.fromValidatedJson(json).withRecordedAt(NOW.minus(Duration.ofDays(days)));
    }

    @Test
    void run_archivesOlderThan90Days_keepsTheRest_recordsEachTenant() {
        AuditEvent old = recordedDaysAgo(91);
        AuditEvent recent = recordedDaysAgo(89);
        repository.add(RED, old);
        repository.add(RED, recent);

        workflow.run();

        assertThat(repository.ids(RED)).containsExactly(recent.eventId());
        LocalDate day = LocalDate.ofInstant(old.recordedAt(), ZoneOffset.UTC);
        byte[] manifest = storage.get(ArchiveFormat.manifestKey("red-logistica", day, 0)).orElseThrow();
        assertThat(ArchiveFormat.manifestFromBytes(manifest).eventIds()).containsExactly(old.eventId());
        List<AuditEvent> archived = ArchiveFormat.eventsFromGzip(
                storage.get(ArchiveFormat.eventsKey("red-logistica", day, 0)).orElseThrow());
        assertThat(archived).containsExactly(old);
        assertThat(repository.outcomes).filteredOn(e -> e.details().get("archived_tenant").asText().equals("red-logistica"))
                .anySatisfy(e -> {
                    assertThat(e.result().wireValue()).isEqualTo("Success");
                    assertThat(e.details().get("event_count").asInt()).isEqualTo(1);
                });
        assertThat(repository.outcomes).anySatisfy(e ->
                assertThat(e.details().get("archived_tenant").asText()).isEqualTo("platform"));
    }

    @Test
    void run_dayWithoutEvents_writesAZeroManifestAndSucceeds() {
        workflow.run();

        LocalDate day = LocalDate.ofInstant(NOW, ZoneOffset.UTC).minusDays(91);
        byte[] manifest = storage.get(ArchiveFormat.manifestKey("red-logistica", day, 0)).orElseThrow();
        assertThat(ArchiveFormat.manifestFromBytes(manifest).eventCount()).isZero();
        assertThat(storage.keys()).noneMatch(k -> k.startsWith("red-logistica/") && k.endsWith(".jsonl.gz"));
        assertThat(repository.outcomes).hasSize(2).allSatisfy(e -> {
            assertThat(e.result().wireValue()).isEqualTo("Success");
            assertThat(e.details().get("archive_ref").asText()).startsWith("s3://test-bucket/");
            assertThat(e.details().get("archive_hash").asText()).startsWith("sha256:");
        });
    }

    @Test
    void run_interruptedBetweenEventsAndManifest_nextRunCompletesTheDay() {
        // The events object is locked as soon as it is written; a retry must reuse it, not try to rewrite it.
        AuditEvent old = recordedDaysAgo(91);
        repository.add(RED, old);
        LocalDate day = LocalDate.ofInstant(old.recordedAt(), ZoneOffset.UTC);
        storage.failNextPutEndingWith(ArchiveFormat.manifestKey("red-logistica", day, 0));

        workflow.run();

        assertThat(repository.ids(RED)).containsExactly(old.eventId());
        assertThat(storage.keys()).contains(ArchiveFormat.eventsKey("red-logistica", day, 0))
                .doesNotContain(ArchiveFormat.manifestKey("red-logistica", day, 0));

        List<ArchiveEventsWorkflow.DayOutcome> retry = workflow.run();

        assertThat(retry).filteredOn(o -> o.tenantId().equals("red-logistica") && o.day().equals(day))
                .singleElement().satisfies(o -> assertThat(o.status()).isEqualTo(ArchiveEventsWorkflow.Status.SUCCESS));
        assertThat(repository.ids(RED)).isEmpty();
        assertThat(ArchiveFormat.manifestFromBytes(storage.get(ArchiveFormat.manifestKey("red-logistica", day, 0))
                .orElseThrow()).eventIds()).containsExactly(old.eventId());
    }

    @Test
    void run_copyFails_deletesNothing_recordsFailure() {
        AuditEvent old = recordedDaysAgo(91);
        repository.add(RED, old);
        storage.unavailable(true);

        workflow.run();

        assertThat(repository.ids(RED)).containsExactly(old.eventId());
        assertThat(repository.outcomes).filteredOn(e -> e.details().get("archived_tenant").asText().equals("red-logistica"))
                .allSatisfy(e -> {
                    assertThat(e.result().wireValue()).isEqualTo("Failure");
                    assertThat(e.reason()).isEqualTo("ColdStorageUnavailable");
                    assertThat(e.details().has("archive_ref")).isFalse();
                });
    }

    @Test
    void run_copyDoesNotReadBackTheSame_deletesNothing() {
        AuditEvent old = recordedDaysAgo(91);
        repository.add(RED, old);
        storage.corruptReads(true);

        workflow.run();

        assertThat(repository.ids(RED)).containsExactly(old.eventId());
        assertThat(repository.outcomes).anySatisfy(e -> assertThat(e.reason()).isEqualTo("CopyMismatch"));
    }

    @Test
    void run_again_doesNotRewrite_andArchivesLateEventsInANewPart() {
        AuditEvent old = recordedDaysAgo(91);
        repository.add(RED, old);
        workflow.run();
        AuditEvent late = AuditEvent.fromValidatedJson(TestEvents.issuanceCompleted("red-logistica", "c", "2Z"))
                .withRecordedAt(old.recordedAt().plusSeconds(1));
        repository.add(RED, late);

        workflow.run();

        LocalDate day = LocalDate.ofInstant(old.recordedAt(), ZoneOffset.UTC);
        assertThat(repository.ids(RED)).isEmpty();
        assertThat(ArchiveFormat.manifestFromBytes(storage.get(ArchiveFormat.manifestKey("red-logistica", day, 1))
                .orElseThrow()).eventIds()).containsExactly(late.eventId());
    }

    @Test
    void archivedObject_isLockedSevenYears_thenExpires() {
        AuditEvent old = recordedDaysAgo(91);
        repository.add(RED, old);
        workflow.run();
        LocalDate day = LocalDate.ofInstant(old.recordedAt(), ZoneOffset.UTC);
        String key = ArchiveFormat.eventsKey("red-logistica", day, 0);

        Instant retainUntil = storage.lockedUntil(key).orElseThrow();

        assertThat(retainUntil).isAfterOrEqualTo(old.recordedAt().atZone(ZoneOffset.UTC).plusYears(7).toInstant());
        assertThat(retainUntil).isBefore(old.recordedAt().atZone(ZoneOffset.UTC).plusYears(7).plusDays(2).toInstant());
    }

    /**
     * In-memory {@link ArchiveRepositoryPort}.
     */
    static final class FakeArchiveRepository implements ArchiveRepositoryPort {

        private final Map<String, List<AuditEvent>> rows = new HashMap<>();
        final List<AuditEvent> outcomes = new ArrayList<>();

        void add(TenantRef tenant, AuditEvent event) {
            rows.computeIfAbsent(tenant.schemaName(), s -> new ArrayList<>()).add(event);
        }

        List<UUID> ids(TenantRef tenant) {
            return rows.getOrDefault(tenant.schemaName(), List.of()).stream().map(AuditEvent::eventId).toList();
        }

        @Override
        public List<LocalDate> daysWithEventsBefore(TenantRef tenant, Instant cutoff) {
            return rows.getOrDefault(tenant.schemaName(), List.of()).stream()
                    .filter(e -> e.recordedAt().isBefore(cutoff))
                    .map(e -> LocalDate.ofInstant(e.recordedAt(), ZoneOffset.UTC)).distinct().sorted().toList();
        }

        @Override
        public List<AuditEvent> eventsRecordedOn(TenantRef tenant, LocalDate day) {
            return rows.getOrDefault(tenant.schemaName(), List.of()).stream()
                    .filter(e -> LocalDate.ofInstant(e.recordedAt(), ZoneOffset.UTC).equals(day)).toList();
        }

        @Override
        public Optional<Instant> oldestRecordedAt(TenantRef tenant) {
            return rows.getOrDefault(tenant.schemaName(), List.of()).stream().map(AuditEvent::recordedAt).min(Instant::compareTo);
        }

        @Override
        public boolean tryLock(String tenantId, LocalDate day, String owner, Instant staleBefore) {
            return true;
        }

        @Override
        public void unlock(String tenantId, LocalDate day, String owner) {
            // nothing to release
        }

        @Override
        public void deleteArchived(TenantRef tenant, Collection<UUID> eventIds, AuditEvent archivedRecord) {
            rows.getOrDefault(tenant.schemaName(), new ArrayList<>()).removeIf(e -> eventIds.contains(e.eventId()));
            outcomes.add(archivedRecord);
        }

        @Override
        public void recordOutcome(AuditEvent archivedRecord) {
            outcomes.add(archivedRecord);
        }
    }
}
