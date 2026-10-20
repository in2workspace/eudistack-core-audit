package com.eudistack.audit.application.workflow;

import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.TenantRef;
import com.eudistack.audit.domain.policy.CanonicalJson;
import com.eudistack.audit.domain.policy.SelfEventFactory;
import com.eudistack.audit.domain.port.out.ArchiveRepositoryPort;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;
import com.eudistack.audit.domain.port.out.ColdStoragePort;
import com.eudistack.audit.domain.port.out.TenantDirectoryPort;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Daily archiving (FR-21, FR-22, FR-24; design §3.4.6).
 *
 * <p>For {@code platform} and every tenant, the events whose {@code recorded_at} is older than 90 days
 * are written to the cold storage, one object per tenant and day, with a manifest; the copy is read
 * back and its hash and compliance lock checked, and only then are those events deleted from the
 * database — by identifier, never by date. Every run of a tenant and day leaves an
 * {@code AuditEventsArchived}; a failure deletes nothing and the next run retries.
 */
public final class ArchiveEventsWorkflow {

    public static final Duration ONLINE_PERIOD = Duration.ofDays(90);
    public static final int RETENTION_YEARS = 7;
    private static final Duration STALE_LOCK = Duration.ofHours(1);
    private static final System.Logger LOG = System.getLogger(ArchiveEventsWorkflow.class.getName());

    private final TenantDirectoryPort directory;
    private final ArchiveRepositoryPort repository;
    private final ColdStoragePort storage;
    private final SelfEventFactory selfEvents;
    private final AuditTelemetryPort telemetry;
    private final Clock clock;

    public ArchiveEventsWorkflow(TenantDirectoryPort directory, ArchiveRepositoryPort repository,
                                 ColdStoragePort storage, SelfEventFactory selfEvents, AuditTelemetryPort telemetry,
                                 Clock clock) {
        this.directory = directory;
        this.repository = repository;
        this.storage = storage;
        this.selfEvents = selfEvents;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    public enum Status { SUCCESS, FAILURE, SKIPPED }

    public record DayOutcome(String tenantId, LocalDate day, Status status, int eventCount, String reason) {
    }

    /**
     * Runs the archiving of every scope. Returns the outcome of each tenant and day processed.
     */
    public List<DayOutcome> run() {
        String runId = UUID.randomUUID().toString();
        LocalDate cutoffDay = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(ONLINE_PERIOD.toDays());
        Instant cutoff = cutoffDay.atStartOfDay(ZoneOffset.UTC).toInstant();
        List<TenantRef> scopes = new ArrayList<>();
        scopes.add(TenantRef.PLATFORM);
        scopes.addAll(directory.tenants());
        return telemetry.span("audit.archive.run", Map.of("audit.archive.day", cutoffDay.minusDays(1).toString()),
                () -> {
                    List<DayOutcome> outcomes = new ArrayList<>();
                    for (TenantRef tenant : scopes) {
                        TreeSet<LocalDate> days = new TreeSet<>(repository.daysWithEventsBefore(tenant, cutoff));
                        // FR-24: one run per tenant even when its day had no events.
                        days.add(cutoffDay.minusDays(1));
                        for (LocalDate day : days) {
                            outcomes.add(archiveDay(tenant, day, runId));
                        }
                        recordLag(tenant);
                    }
                    return outcomes;
                });
    }

    /**
     * Archives one tenant and day. Safe to run again: existing objects are verified, never rewritten.
     */
    public DayOutcome archiveDay(TenantRef tenant, LocalDate day, String runId) {
        Instant now = clock.instant();
        if (!repository.tryLock(tenant.tenantId(), day, runId, now.minus(STALE_LOCK))) {
            return new DayOutcome(tenant.tenantId(), day, Status.SKIPPED, 0, "locked");
        }
        try {
            return telemetry.span("audit.archive.tenant", Map.of("audit.tenant", tenant.tenantId()),
                    () -> archiveLocked(tenant, day, runId));
        } finally {
            repository.unlock(tenant.tenantId(), day, runId);
        }
    }

    private DayOutcome archiveLocked(TenantRef tenant, LocalDate day, String runId) {
        Instant periodFrom = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant periodTo = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<AuditEvent> rows = repository.eventsRecordedOn(tenant, day);
        try {
            List<StoredManifest> existing = readManifests(tenant.tenantId(), day);
            Set<UUID> archived = new HashSet<>();
            existing.forEach(m -> archived.addAll(m.manifest().eventIds()));
            List<AuditEvent> pending = rows.stream().filter(e -> !archived.contains(e.eventId())).toList();

            StoredManifest manifest;
            if (!pending.isEmpty() || existing.isEmpty()) {
                manifest = writePart(tenant.tenantId(), day, existing.size(), pending);
            } else {
                manifest = existing.getLast();
            }
            List<UUID> toDelete = rows.stream().map(AuditEvent::eventId)
                    .filter(id -> archived.contains(id) || manifest.manifest().eventIds().contains(id))
                    .toList();
            AuditEvent record = selfEvents.archived(tenant.tenantId(), periodFrom, periodTo, toDelete.size(), null,
                    storage.reference(manifest.key()), CanonicalJson.hashRef(CanonicalJson.sha256(manifest.bytes())),
                    runId);
            repository.deleteArchived(tenant, toDelete, record);
            telemetry.archiveRun(true);
            return new DayOutcome(tenant.tenantId(), day, Status.SUCCESS, toDelete.size(), null);
        } catch (ColdStoragePort.ColdStorageException e) {
            return fail(tenant, day, periodFrom, periodTo, rows.size(), "ColdStorageUnavailable", runId, e);
        } catch (CopyMismatchException | java.io.UncheckedIOException e) {
            return fail(tenant, day, periodFrom, periodTo, rows.size(), "CopyMismatch", runId, e);
        }
    }

    private StoredManifest writePart(String tenantId, LocalDate day, int part, List<AuditEvent> events) {
        Instant retainUntil = retainUntil(day);
        String eventsKey = null;
        String eventsHash = null;
        byte[] eventsBytes = null;
        if (!events.isEmpty()) {
            eventsKey = ArchiveFormat.eventsKey(tenantId, day, part);
            eventsBytes = ArchiveFormat.eventsToGzip(events);
            eventsHash = CanonicalJson.hashRef(CanonicalJson.sha256(eventsBytes));
            putOnce(eventsKey, eventsBytes, "application/gzip", retainUntil);
        }
        ArchiveFormat.Manifest manifest = new ArchiveFormat.Manifest(tenantId, day, part, events.size(), eventsKey,
                eventsHash, events.stream().map(AuditEvent::eventId).toList(), clock.instant(), retainUntil);
        String manifestKey = ArchiveFormat.manifestKey(tenantId, day, part);
        byte[] manifestBytes = ArchiveFormat.manifestToBytes(manifest);
        storage.put(manifestKey, manifestBytes, "application/json", retainUntil);

        // Read back before deleting anything (AD-7, R-4).
        if (eventsKey != null) {
            byte[] stored = storage.get(eventsKey).orElseThrow(() -> new CopyMismatchException("events object missing"));
            if (!Arrays.equals(stored, eventsBytes)) {
                throw new CopyMismatchException("events object differs from what was written");
            }
            requireLock(eventsKey, retainUntil);
        }
        byte[] storedManifest = storage.get(manifestKey)
                .orElseThrow(() -> new CopyMismatchException("manifest missing"));
        if (!Arrays.equals(storedManifest, manifestBytes)) {
            throw new CopyMismatchException("manifest differs from what was written");
        }
        requireLock(manifestKey, retainUntil);
        return new StoredManifest(manifestKey, manifestBytes, manifest);
    }

    /**
     * Writes an object unless a previous run already did. A run that stopped after the events object and
     * before its manifest leaves that object locked: the retry finds it, checks that it holds exactly the
     * same bytes (the format is deterministic) and goes on, instead of failing on the write-once guard
     * every day from then on.
     */
    private void putOnce(String key, byte[] content, String contentType, Instant retainUntil) {
        Optional<byte[]> existing = storage.get(key);
        if (existing.isEmpty()) {
            storage.put(key, content, contentType, retainUntil);
        } else if (!Arrays.equals(existing.get(), content)) {
            throw new CopyMismatchException("an earlier run left a different object: " + key);
        }
    }

    private List<StoredManifest> readManifests(String tenantId, LocalDate day) {
        List<String> keys = storage.list(ArchiveFormat.dayPrefix(tenantId, day)).stream()
                .filter(ArchiveFormat::isManifestKey).toList();
        List<StoredManifest> manifests = new ArrayList<>();
        for (String key : keys) {
            byte[] bytes = storage.get(key).orElseThrow(() -> new CopyMismatchException("manifest vanished: " + key));
            ArchiveFormat.Manifest manifest = ArchiveFormat.manifestFromBytes(bytes);
            verifyEvents(manifest);
            manifests.add(new StoredManifest(key, bytes, manifest));
        }
        manifests.sort((a, b) -> Integer.compare(a.manifest().part(), b.manifest().part()));
        return manifests;
    }

    /**
     * Checks that the events object of a manifest is there and has the hash the manifest declares.
     */
    List<AuditEvent> verifyEvents(ArchiveFormat.Manifest manifest) {
        if (manifest.eventsKey() == null) {
            if (manifest.eventCount() != 0) {
                throw new CopyMismatchException("manifest declares events but no object");
            }
            return List.of();
        }
        byte[] content = storage.get(manifest.eventsKey())
                .orElseThrow(() -> new CopyMismatchException("events object missing: " + manifest.eventsKey()));
        if (!CanonicalJson.hashRef(CanonicalJson.sha256(content)).equals(manifest.eventsHash())) {
            throw new CopyMismatchException("hash mismatch: " + manifest.eventsKey());
        }
        List<AuditEvent> events = ArchiveFormat.eventsFromGzip(content);
        if (events.size() != manifest.eventCount()) {
            throw new CopyMismatchException("count mismatch: " + manifest.eventsKey());
        }
        return events;
    }

    private void requireLock(String key, Instant retainUntil) {
        Optional<Instant> locked = storage.lockedUntil(key);
        if (locked.isEmpty() || locked.get().isBefore(retainUntil)) {
            throw new CopyMismatchException("object is not locked until " + retainUntil + ": " + key);
        }
    }

    private DayOutcome fail(TenantRef tenant, LocalDate day, Instant from, Instant to, int count, String reason,
                            String runId, RuntimeException cause) {
        LOG.log(System.Logger.Level.ERROR, "Archiving of " + tenant.tenantId() + " " + day + " failed: " + reason,
                cause);
        repository.recordOutcome(selfEvents.archived(tenant.tenantId(), from, to, count, reason, null, null, runId));
        telemetry.archiveRun(false);
        return new DayOutcome(tenant.tenantId(), day, Status.FAILURE, count, reason);
    }

    private void recordLag(TenantRef tenant) {
        long seconds = repository.oldestRecordedAt(tenant)
                .map(oldest -> Math.max(0, Duration.between(oldest, clock.instant()).toSeconds()))
                .orElse(0L);
        telemetry.archiveLag(tenant.tenantId(), tenant.isPlatform(), seconds);
    }

    /**
     * End of the day plus seven years: never earlier than {@code recorded_at + 7 years} of any event of the day.
     */
    static Instant retainUntil(LocalDate day) {
        return day.plusDays(1).plusYears(RETENTION_YEARS).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    record StoredManifest(String key, byte[] bytes, ArchiveFormat.Manifest manifest) {
    }

    static final class CopyMismatchException extends RuntimeException {
        CopyMismatchException(String message) {
            super(message);
        }
    }
}
