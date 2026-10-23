package com.eudistack.audit.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Audit event: canonical record plus the {@code details} of its type.
 *
 * <p>{@code actor} and {@code details} are kept as JSON, exactly as received (FR-16): the catalogue
 * validates them, the API never reshapes them. {@code recordedAt} is {@code null} until the store
 * sets it.
 */
public record AuditEvent(
        UUID eventId,
        String eventType,
        String eventVersion,
        Instant occurredAt,
        Instant recordedAt,
        String tenant,
        String organization,
        String source,
        JsonNode actor,
        String action,
        String resource,
        Result result,
        String reason,
        String correlationId,
        JsonNode details
) {

    private static final String JSON_TENANT = "tenant";
    private static final String JSON_SOURCE = "source";
    private static final String JSON_ACTOR = "actor";
    private static final String JSON_ACTION = "action";
    private static final String JSON_RESOURCE = "resource";
    private static final String JSON_RESULT = "result";
    private static final String JSON_DETAILS = "details";

    public AuditEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(eventVersion, "eventVersion");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(tenant, JSON_TENANT);
        Objects.requireNonNull(source, JSON_SOURCE);
        Objects.requireNonNull(actor, JSON_ACTOR);
        Objects.requireNonNull(action, JSON_ACTION);
        Objects.requireNonNull(resource, JSON_RESOURCE);
        Objects.requireNonNull(result, JSON_RESULT);
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(details, JSON_DETAILS);
    }

    /**
     * Builds the event from a received JSON object that {@code CatalogueValidator} already accepted.
     */
    public static AuditEvent fromValidatedJson(JsonNode node) {
        return new AuditEvent(
                UUID.fromString(node.get("event_id").asText()),
                node.get("event_type").asText(),
                node.get("event_version").asText(),
                OffsetDateTime.parse(node.get("occurred_at").asText()).toInstant(),
                null,
                node.get(JSON_TENANT).asText(),
                textOrNull(node, "organization"),
                node.get(JSON_SOURCE).asText(),
                node.get(JSON_ACTOR),
                node.get(JSON_ACTION).asText(),
                node.get(JSON_RESOURCE).asText(),
                Result.fromWire(node.get(JSON_RESULT).asText()).orElseThrow(),
                textOrNull(node, "reason"),
                node.get("correlation_id").asText(),
                node.get(JSON_DETAILS));
    }

    /**
     * Builds the event from its stored JSON ({@link #toJson()}), as written to the cold storage.
     */
    public static AuditEvent fromStoredJson(JsonNode node) {
        AuditEvent event = fromValidatedJson(node);
        String recorded = textOrNull(node, "recorded_at");
        return recorded == null ? event : event.withRecordedAt(Instant.parse(recorded));
    }

    public AuditEvent withTenant(String newTenant) {
        return new AuditEvent(eventId, eventType, eventVersion, occurredAt, recordedAt, newTenant, organization,
                source, actor, action, resource, result, reason, correlationId, details);
    }

    public AuditEvent withRecordedAt(Instant newRecordedAt) {
        return new AuditEvent(eventId, eventType, eventVersion, occurredAt, newRecordedAt, tenant, organization,
                source, actor, action, resource, result, reason, correlationId, details);
    }

    /**
     * JSON in the shape of {@code AuditEvent} of the OpenAPI contract. Optional fields that are absent
     * are omitted, never written as {@code null}.
     */
    public ObjectNode toJson() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("event_id", eventId.toString());
        node.put("event_type", eventType);
        node.put("event_version", eventVersion);
        node.put("occurred_at", occurredAt.toString());
        if (recordedAt != null) {
            node.put("recorded_at", recordedAt.toString());
        }
        node.put(JSON_TENANT, tenant);
        if (organization != null) {
            node.put("organization", organization);
        }
        node.put(JSON_SOURCE, source);
        node.set(JSON_ACTOR, actor.deepCopy());
        node.put(JSON_ACTION, action);
        node.put(JSON_RESOURCE, resource);
        node.put(JSON_RESULT, result.wireValue());
        if (reason != null) {
            node.put("reason", reason);
        }
        node.put("correlation_id", correlationId);
        node.set(JSON_DETAILS, details.deepCopy());
        return node;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
