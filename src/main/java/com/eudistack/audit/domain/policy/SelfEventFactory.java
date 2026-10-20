package com.eudistack.audit.domain.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.model.RejectionReason;
import com.eudistack.audit.domain.model.Result;
import com.eudistack.audit.domain.model.TenantRef;
import com.eudistack.audit.domain.model.Violation;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Builds the events the audit API writes about itself (design §3.4.8, PO-11 validated):
 * {@code AuditEventRejected}, {@code AuditWriteDenied}, {@code AuditTrailQueried} and
 * {@code AuditEventsArchived}. Their shape follows the catalogue version this API runs with.
 */
public final class SelfEventFactory {

    public static final String SOURCE_API = "audit-api";
    public static final String SOURCE_ARCHIVER = "audit-archiver";
    public static final String OPERATION_LIST = "List";
    public static final String OPERATION_GET = "Get";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final Clock clock;
    private final String catalogueVersion;

    public SelfEventFactory(Clock clock, String catalogueVersion) {
        this.clock = clock;
        this.catalogueVersion = catalogueVersion;
    }

    /**
     * {@code AuditEventRejected}, stored in {@code platform}. Its {@code event_id} is derived from the
     * received content, so a resent batch does not leave a second rejection.
     *
     * @param original            the event exactly as received
     * @param fallbackCorrelation {@code trace_id} of the request, used when the event has no {@code correlation_id}
     */
    public AuditEvent rejected(JsonNode original, RejectionReason reason, List<Violation> violations,
                               String fallbackCorrelation) {
        // The id comes from the bytes as received; what is stored cannot hold U+0000.
        UUID eventId = IdempotencyPolicy.rejectionEventId(original == null ? JSON.nullNode() : original);
        JsonNode received = NulCharacters.strip(original);
        ObjectNode details = JSON.objectNode();
        String declaredTenant = textField(received, "tenant");
        String declaredType = textField(received, "event_type");
        if (declaredTenant != null) {
            details.put("declared_tenant", declaredTenant);
        }
        if (declaredType != null) {
            details.put("declared_event_type", declaredType);
        }
        if (received != null && received.isObject()) {
            details.set("received_event", received.deepCopy());
        } else {
            details.set("received_event", JSON.objectNode().set("value",
                    received == null ? JSON.nullNode() : received.deepCopy()));
        }
        if (reason == RejectionReason.INVALID_EVENT && !violations.isEmpty()) {
            ArrayNode list = details.putArray("violations");
            violations.forEach(v -> list.addObject()
                    .put("field", NulCharacters.strip(v.field()))
                    .put("message", NulCharacters.strip(v.message())));
        }
        String receivedId = textField(received, "event_id");
        String correlation = textField(received, "correlation_id");
        return new AuditEvent(
                eventId,
                "AuditEventRejected", catalogueVersion, now(), null, TenantRef.PLATFORM_ID, null, SOURCE_API,
                systemActor(SOURCE_API), "RejectAuditEvent",
                "audit-event:" + (receivedId == null || receivedId.isBlank() ? "unknown" : receivedId),
                Result.DENIED, reason.catalogueReason(),
                correlation == null || correlation.isBlank() ? fallbackCorrelation : correlation,
                details);
    }

    /**
     * {@code AuditWriteDenied}, stored in {@code platform}.
     *
     * @param caller     the authenticated caller, or {@code null} when the token could not be verified
     * @param reason     {@code InvalidToken} or {@code PermissionMissing}
     * @param eventCount size of the batch when its JSON could be read, 0 otherwise
     */
    public AuditEvent writeDenied(Caller caller, String reason, int eventCount, String traceId) {
        ObjectNode details = JSON.objectNode().put("event_count", eventCount);
        return new AuditEvent(UUID.randomUUID(), "AuditWriteDenied", catalogueVersion, now(), null,
                TenantRef.PLATFORM_ID, caller == null ? null : caller.organization(), SOURCE_API,
                caller == null ? systemActor("anonymous") : caller.toActorJson(), "WriteAuditTrail", "audit-write",
                Result.DENIED, reason, traceId, details);
    }

    /**
     * {@code AuditTrailQueried}, stored in the tenant of the query (design §3.4.5).
     *
     * @param filters       the query parameters as received, for {@code List}; ignored for {@code Get}
     * @param reason        {@code null} on success
     * @param returnedCount number of events returned, only on success
     */
    public AuditEvent trailQueried(Caller caller, TenantRef target, String operation, String queriedScope,
                                   ObjectNode filters, String reason, Integer returnedCount, String traceId) {
        ObjectNode details = JSON.objectNode();
        details.put("operation", operation);
        details.put("queried_scope", queriedScope);
        if (OPERATION_LIST.equals(operation)) {
            details.set("filters", filters == null ? JSON.objectNode() : filters.deepCopy());
        }
        if (reason == null && returnedCount != null) {
            details.put("returned_count", returnedCount);
        }
        return new AuditEvent(UUID.randomUUID(), "AuditTrailQueried", catalogueVersion, now(), null,
                target.tenantId(), caller.organization(), SOURCE_API, caller.toActorJson(), "QueryAuditTrail",
                OPERATION_LIST.equals(operation) ? "audit-query:list" : "audit-query:get",
                reason == null ? Result.SUCCESS : Result.DENIED, reason, traceId, details);
    }

    /**
     * {@code AuditEventsArchived}, stored in {@code platform}.
     */
    public AuditEvent archived(String archivedTenant, Instant periodFrom, Instant periodTo, int eventCount,
                               String failureReason, String archiveRef, String archiveHash, String runId) {
        ObjectNode details = JSON.objectNode();
        details.put("archived_tenant", archivedTenant);
        details.put("period_from", periodFrom.toString());
        details.put("period_to", periodTo.toString());
        details.put("event_count", eventCount);
        if (failureReason == null) {
            details.put("archive_ref", archiveRef);
            details.put("archive_hash", archiveHash);
        }
        return new AuditEvent(UUID.randomUUID(), "AuditEventsArchived", catalogueVersion, now(), null,
                TenantRef.PLATFORM_ID, null, SOURCE_ARCHIVER, systemActor(SOURCE_ARCHIVER), "ArchiveAuditTrail",
                "audit-archive:" + archivedTenant + ":" + periodFrom.toString().substring(0, 10),
                failureReason == null ? Result.SUCCESS : Result.FAILURE, failureReason, runId, details);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ObjectNode systemActor(String id) {
        return JSON.objectNode().put("kind", "System").put("id", id);
    }

    private static String textField(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
