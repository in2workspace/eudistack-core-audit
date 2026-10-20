package com.eudistack.audit.application.workflow;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eudistack.audit.domain.exception.BadRequestException;
import com.eudistack.audit.domain.exception.InsufficientPermissionException;
import com.eudistack.audit.domain.exception.TenantNotResolvedException;
import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.model.CursorPosition;
import com.eudistack.audit.domain.model.TenantRef;
import com.eudistack.audit.domain.policy.AccessPolicy;
import com.eudistack.audit.domain.policy.SelfEventFactory;
import com.eudistack.audit.domain.port.out.ColdStoragePort;
import com.eudistack.audit.domain.port.out.SelfAuditPort;
import com.eudistack.audit.domain.port.out.TenantDirectoryPort;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Recovery of an archived period (FR-23, AC-22). Internal operation of the archiver, run by the platform
 * administrator on a support request: it is not a path of the published contract.
 *
 * <p>Every manifest of the period is re-verified against its events object before anything is
 * delivered; what is delivered is exactly what was stored. The recovery leaves an
 * {@code AuditTrailQueried} in the recovered tenant.
 */
public final class RecoverArchiveWorkflow {

    static final int MAX_DAYS = 366;

    private final TenantDirectoryPort directory;
    private final ColdStoragePort storage;
    private final ArchiveEventsWorkflow verifier;
    private final SelfAuditPort selfAudit;
    private final SelfEventFactory selfEvents;

    public RecoverArchiveWorkflow(TenantDirectoryPort directory, ColdStoragePort storage,
                                  ArchiveEventsWorkflow verifier, SelfAuditPort selfAudit,
                                  SelfEventFactory selfEvents) {
        this.directory = directory;
        this.storage = storage;
        this.verifier = verifier;
        this.selfAudit = selfAudit;
        this.selfEvents = selfEvents;
    }

    /**
     * @param from first UTC day, by {@code recorded_at}
     * @param to   last UTC day, inclusive
     */
    public List<AuditEvent> recover(Caller caller, String tenantId, LocalDate from, LocalDate to, String traceId) {
        if (!AccessPolicy.isPlatformAdministrator(caller)) {
            throw new InsufficientPermissionException("Recovering an archived period requires System · Administration");
        }
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new BadRequestException("period", "The period must be 1 to " + MAX_DAYS + " days");
        }
        String normalised = TenantRef.normalise(tenantId);
        Optional<TenantRef> tenant = TenantRef.PLATFORM_ID.equals(normalised)
                ? Optional.of(TenantRef.PLATFORM) : directory.find(normalised);
        if (tenant.isEmpty()) {
            throw new TenantNotResolvedException("Unknown tenant: " + tenantId);
        }
        List<AuditEvent> events = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            for (String key : storage.list(ArchiveFormat.dayPrefix(tenant.get().tenantId(), day))) {
                if (ArchiveFormat.isManifestKey(key)) {
                    byte[] bytes = storage.get(key).orElseThrow();
                    events.addAll(verifier.verifyEvents(ArchiveFormat.manifestFromBytes(bytes)));
                }
            }
        }
        events.sort(Comparator.comparing(e -> new CursorPosition(e.occurredAt(), e.eventId(), null)));
        ObjectNode filters = JsonNodeFactory.instance.objectNode()
                .put("source", "archive").put("from", from.toString()).put("to", to.toString());
        selfAudit.record(tenant.get(), selfEvents.trailQueried(caller, tenant.get(), SelfEventFactory.OPERATION_LIST,
                tenant.get().tenantId(), filters, null, events.size(), traceId));
        return events;
    }
}
