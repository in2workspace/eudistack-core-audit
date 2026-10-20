package com.eudistack.audit.domain.catalogue;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Where a condition looks up its identifiers: first the object that holds the field (an array item
 * for {@code attribute_evidence}), then {@code details}, then the canonical record.
 */
public record ConditionScope(JsonNode current, JsonNode details, JsonNode event) {

    /**
     * Scalar value of an identifier as text, or {@code null} when it is absent or not a scalar.
     */
    public String valueOf(String identifier) {
        for (JsonNode node : new JsonNode[] {current, details, event}) {
            if (node != null && node.isObject() && node.has(identifier)) {
                JsonNode value = node.get(identifier);
                return value.isValueNode() && !value.isNull() ? value.asText() : null;
            }
        }
        return null;
    }

    public ConditionScope nested(JsonNode newCurrent) {
        return new ConditionScope(newCurrent, details, event);
    }
}
