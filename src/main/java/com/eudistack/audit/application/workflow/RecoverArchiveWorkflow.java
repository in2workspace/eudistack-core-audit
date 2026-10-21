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
import com.eudistack.audit.domain.policy.ScopePolicy;
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
 * {@code AuditTrailQueried} in the recovered tenant; a refused one leaves it {@code Denied} (FR-17), in
 * the recovered tenant or, when the tenant does not resolve, in {@code platform}. As on the API, a
 * malformed period leaves none (AD-8).
 */
public final class RecoverArchiveWorkflow {

    private static final System.Logger LOG = System.getLogger(RecoverArchiveWorkflow.class.getName());

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
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new BadRequestException("period", "The period must be 1 to " + MAX_DAYS + " days");
        }
        ObjectNode filters = JsonNodeFactory.instance.objectNode()
                .put("source", "archive").put("from", from.toString()).put("to", to.toString());
        String normalised = TenantRef.normalise(tenantId);
        Optional<TenantRef> tenant = TenantRef.PLATFORM_ID.equals(normalised)
                ? Optional.of(TenantRef.PLATFORM) : directory.find(normalised);
        if (!AccessPolicy.isPlatformAdministrator(caller)) {
            recordDenial(caller, tenant.orElse(TenantRef.PLATFORM), normalised, filters,
                    ScopePolicy.DenialReason.PERMISSION_MISSING, traceId);
            throw new InsufficientPermissionException("Recovering an archived period requires System · Administration");
        }
        if (tenant.isEmpty()) {
            recordDenial(caller, TenantRef.PLATFORM, normalised, filters, ScopePolicy.DenialReason.TENANT_NOT_RESOLVED,
                    traceId);
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
        selfAudit.record(tenant.get(), selfEvents.trailQueried(caller, tenant.get(), SelfEventFactory.OPERATION_LIST,
                tenant.get().tenantId(), filters, null, events.size(), traceId));
        return events;
    }

    /** Best effort, like the API: the refusal never depends on the store. */
    private void recordDenial(Caller caller, TenantRef target, String label, ObjectNode filters,
                              ScopePolicy.DenialReason reason, String traceId) {
        try {
            selfAudit.record(target, selfEvents.trailQueried(caller, target, SelfEventFactory.OPERATION_LIST, label,
                    filters, reason.catalogueReason(), null, traceId));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "Denied AuditTrailQueried of a recovery could not be stored", e);
        }
    }
}
