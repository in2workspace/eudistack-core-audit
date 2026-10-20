package com.eudistack.audit.domain.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * U+0000 in received JSON. PostgreSQL {@code text} and {@code jsonb} cannot hold it, so an event that
 * carries it can never be stored: it is rejected as invalid, and the copy kept in its
 * {@code AuditEventRejected} has it replaced by U+FFFD. Otherwise the insert would fail the whole batch
 * with a 503 that the producer retries forever.
 */
public final class NulCharacters {

    static final char NUL = '\u0000';
    static final String REPLACEMENT = "�";

    private NulCharacters() {
    }

    /**
     * Dot path of the first field whose name or text value holds U+0000, empty when there is none.
     */
    public static Optional<String> firstPath(JsonNode node) {
        return firstPath(node, "");
    }

    private static Optional<String> firstPath(JsonNode node, String path) {
        if (node == null) {
            return Optional.empty();
        }
        if (node.isTextual()) {
            return node.textValue().indexOf(NUL) >= 0 ? Optional.of(path) : Optional.empty();
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                Optional<String> found = firstPath(node.get(i), path + "[" + i + "]");
                if (found.isPresent()) {
                    return found;
                }
            }
        } else if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.properties().iterator(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                String child = path.isEmpty() ? field.getKey() : path + "." + field.getKey();
                if (field.getKey().indexOf(NUL) >= 0) {
                    return Optional.of(strip(child));
                }
                Optional<String> found = firstPath(field.getValue(), child);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Copy of the node with U+0000 replaced by U+FFFD in every field name and text value.
     */
    public static JsonNode strip(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            return JsonNodeFactory.instance.textNode(strip(node.textValue()));
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode(node.size());
            node.forEach(item -> copy.add(strip(item)));
            return copy;
        }
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            node.properties().forEach(field -> copy.set(strip(field.getKey()), strip(field.getValue())));
            return copy;
        }
        return node.deepCopy();
    }

    public static String strip(String value) {
        return value == null || value.indexOf(NUL) < 0 ? value : value.replace(String.valueOf(NUL), REPLACEMENT);
    }
}
