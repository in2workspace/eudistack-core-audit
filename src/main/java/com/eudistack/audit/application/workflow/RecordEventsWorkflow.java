package com.eudistack.audit.application.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.eudistack.audit.domain.catalogue.CatalogueValidator;
import com.eudistack.audit.domain.catalogue.ValidationResult;
import com.eudistack.audit.domain.exception.BadRequestException;
import com.eudistack.audit.domain.exception.InsufficientPermissionException;
import com.eudistack.audit.domain.exception.ServiceUnavailableException;
import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.model.EventBatch;
import com.eudistack.audit.domain.model.EventOutcome;
import com.eudistack.audit.domain.model.RejectionReason;
import com.eudistack.audit.domain.model.TenantRef;
import com.eudistack.audit.domain.model.Violation;
import com.eudistack.audit.domain.policy.AccessPolicy;
import com.eudistack.audit.domain.policy.IdempotencyPolicy;
import com.eudistack.audit.domain.policy.SelfEventFactory;
import com.eudistack.audit.domain.port.in.RecordEventsPort;
import com.eudistack.audit.domain.port.out.AuditEventRepositoryPort;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;
import com.eudistack.audit.domain.port.out.BatchWriter;
import com.eudistack.audit.domain.port.out.SelfAuditPort;
import com.eudistack.audit.domain.port.out.TenantDirectoryPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ingestion of a batch (design §3.4.4).
 *
 * <ol>
 *   <li>The caller must hold {@code organization · EUDISTACK · Audit · Write} and no read power;
 *       otherwise {@code AuditWriteDenied} and 403.</li>
 *   <li>A malformed batch is a 400 and nothing is stored.</li>
 *   <li>Every event is validated against the catalogue and its tenant resolved before the transaction,
 *       so the connection is not held while validating.</li>
 *   <li>One transaction for the whole batch: valid events go to the schema of their tenant, rejected
 *       ones to {@code platform} as {@code AuditEventRejected}. Either everything commits or nothing does.</li>
 * </ol>
 */
public final class RecordEventsWorkflow implements RecordEventsPort {

    private static final System.Logger LOG = System.getLogger(RecordEventsWorkflow.class.getName());

    private final CatalogueValidator validator;
    private final TenantDirectoryPort directory;
    private final AuditEventRepositoryPort repository;
    private final SelfAuditPort selfAudit;
    private final SelfEventFactory selfEvents;
    private final AuditTelemetryPort telemetry;

    public RecordEventsWorkflow(CatalogueValidator validator, TenantDirectoryPort directory,
                                AuditEventRepositoryPort repository, SelfAuditPort selfAudit,
                                SelfEventFactory selfEvents, AuditTelemetryPort telemetry) {
        this.validator = validator;
        this.directory = directory;
        this.repository = repository;
        this.selfAudit = selfAudit;
        this.selfEvents = selfEvents;
        this.telemetry = telemetry;
    }

    @Override
    public List<EventOutcome> record(Caller caller, JsonNode body, String traceId) {
        long start = System.nanoTime();
        if (!AccessPolicy.canWrite(caller)) {
            telemetry.writeDenied("permission");
            denyWrite(caller, "PermissionMissing", EventBatch.countEvents(body), traceId);
            throw new InsufficientPermissionException("The caller lacks organization · EUDISTACK · Audit · Write");
        }
        EventBatch batch;
        try {
            batch = EventBatch.parse(body);
        } catch (BadRequestException e) {
            telemetry.batchRecorded("rejected_request", System.nanoTime() - start);
            throw e;
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("audit.batch.size", Integer.toString(batch.events().size()));
        singleCorrelation(batch).ifPresent(c -> attributes.put("audit.correlation_id", c));
        return telemetry.span("audit.batch.record", attributes, () -> {
            List<Prepared> prepared = batch.events().stream().map(this::prepare).toList();
            try {
                List<EventOutcome> outcomes = repository.writeBatch(writer -> persist(writer, prepared, traceId));
                telemetry.batchRecorded("ok", System.nanoTime() - start);
                outcomes.forEach(o -> telemetry.eventRecorded(o.status().wireValue(),
                        o.reason() == null ? "none" : o.reason().errorType()));
                return outcomes;
            } catch (ServiceUnavailableException e) {
                telemetry.batchRecorded("unavailable", System.nanoTime() - start);
                throw e;
            }
        });
    }

    @Override
    public void recordAuthenticationFailure(int eventCount, String traceId) {
        telemetry.writeDenied("invalid_token");
        denyWrite(null, "InvalidToken", eventCount, traceId);
    }

    private void denyWrite(Caller caller, String reason, int eventCount, String traceId) {
        try {
            selfAudit.record(TenantRef.PLATFORM, selfEvents.writeDenied(caller, reason, eventCount, traceId));
        } catch (RuntimeException e) {
            // FR-11 only while the store answers: the 401/403 is returned anyway.
            LOG.log(System.Logger.Level.ERROR, "AuditWriteDenied could not be stored", e);
        }
    }

    private Prepared prepare(JsonNode received) {
        long start = System.nanoTime();
        ValidationResult result = telemetry.span("audit.catalog.validate", catalogueAttributes(received),
                () -> validator.validate(received));
        telemetry.catalogueValidated(result instanceof ValidationResult.Valid, System.nanoTime() - start);
        return switch (result) {
            case ValidationResult.Invalid invalid -> new Rejected(received, invalid.reason(), invalid.violations());
            case ValidationResult.Valid valid -> resolveTenant(valid);
        };
    }

    private Prepared resolveTenant(ValidationResult.Valid valid) {
        String tenantId = TenantRef.normalise(valid.event().tenant());
        Optional<TenantRef> tenant = TenantRef.PLATFORM_ID.equals(tenantId)
                ? Optional.of(TenantRef.PLATFORM) : directory.find(tenantId);
        if (tenant.isEmpty()) {
            return new Rejected(valid.received(), RejectionReason.UNKNOWN_TENANT, List.of());
        }
        return new ToStore(valid.received(), valid.event().withTenant(tenant.get().tenantId()), tenant.get(),
                IdempotencyPolicy.payloadHash(valid.received()));
    }

    private List<EventOutcome> persist(BatchWriter writer, List<Prepared> prepared, String traceId) {
        List<EventOutcome> outcomes = new ArrayList<>(prepared.size());
        for (Prepared item : prepared) {
            outcomes.add(switch (item) {
                case ToStore store -> telemetry.span("audit.event.persist", persistAttributes(store),
                        () -> store(writer, store, traceId));
                case Rejected rejected -> reject(writer, rejected.received(), rejected.reason(),
                        rejected.violations(), traceId);
            });
        }
        return outcomes;
    }

    private EventOutcome store(BatchWriter writer, ToStore store, String traceId) {
        String eventId = store.event().eventId().toString();
        return switch (writer.insertIfAbsent(store.tenant(), store.event(), store.payloadHash())) {
            case INSERTED -> EventOutcome.accepted(eventId);
            case SAME_CONTENT -> EventOutcome.duplicate(eventId);
            case DIFFERENT_CONTENT -> reject(writer, store.received(), RejectionReason.EVENT_ID_CONFLICT, List.of(),
                    traceId);
        };
    }

    private EventOutcome reject(BatchWriter writer, JsonNode received, RejectionReason reason,
                                List<Violation> violations, String traceId) {
        AuditEvent rejection = selfEvents.rejected(received, reason, violations, traceId);
        writer.insertPlatformEvent(rejection);
        return EventOutcome.rejected(receivedEventId(received), reason, violations);
    }

    private static String receivedEventId(JsonNode received) {
        JsonNode id = received != null && received.isObject() ? received.get("event_id") : null;
        return id != null && id.isTextual() ? id.asText() : "";
    }

    private static Optional<String> singleCorrelation(EventBatch batch) {
        Set<String> ids = batch.events().stream()
                .map(e -> e.isObject() && e.hasNonNull("correlation_id") ? e.get("correlation_id").asText() : "")
                .collect(Collectors.toSet());
        return ids.size() == 1 && !ids.contains("") ? ids.stream().findFirst() : Optional.empty();
    }

    private static Map<String, String> catalogueAttributes(JsonNode received) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (received != null && received.isObject()) {
            attributes.put("audit.event_type", received.path("event_type").asText(""));
            attributes.put("audit.event_version", received.path("event_version").asText(""));
        }
        return attributes;
    }

    private static Map<String, String> persistAttributes(ToStore store) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("audit.event_id", store.event().eventId().toString());
        attributes.put("audit.event_type", store.event().eventType());
        attributes.put("audit.correlation_id", store.event().correlationId());
        attributes.put("audit.tenant", store.tenant().tenantId());
        return attributes;
    }

    private sealed interface Prepared permits ToStore, Rejected {
    }

    private record ToStore(JsonNode received, AuditEvent event, TenantRef tenant, byte[] payloadHash)
            implements Prepared {
    }

    private record Rejected(JsonNode received, RejectionReason reason, List<Violation> violations)
            implements Prepared {
    }
}
