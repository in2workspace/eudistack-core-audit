package com.eudistack.audit.infrastructure.catalogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.eudistack.audit.domain.catalogue.CatalogueVersion;
import com.eudistack.audit.domain.catalogue.EventTypeSpec;
import com.eudistack.audit.domain.catalogue.FieldSpec;
import com.eudistack.audit.domain.catalogue.FieldType;
import com.eudistack.audit.domain.catalogue.RequiredRule;
import com.eudistack.audit.domain.model.Result;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns one {@code catalogo-eventos.yaml} into a {@link CatalogueVersion}. Fails fast on anything it
 * does not understand: a catalogue that cannot be read must stop the service, not accept everything.
 */
public final class CatalogueYamlParser {

    private static final Set<String> CANONICAL = Set.of("event_id", "event_type", "event_version", "occurred_at",
            "recorded_at", "tenant", "organization", "source", "actor", "action", "resource", "result", "reason",
            "correlation_id", "details");

    private static final Map<String, FieldType> SCALARS = Map.of(
            "string", FieldType.STRING,
            "boolean", FieldType.BOOLEAN,
            "integer", FieldType.INTEGER,
            "date-time", FieldType.DATE_TIME,
            "uri", FieldType.URI,
            "email", FieldType.EMAIL,
            "uuid", FieldType.UUID,
            "semver", FieldType.SEMVER);

    private final Map<String, JsonNode> namedTypes = new HashMap<>();

    public CatalogueVersion parse(JsonNode root) {
        String version = required(root, "catalog_version").asText();
        namedTypes.clear();
        root.path("types").properties().forEach(e -> namedTypes.put(e.getKey(), e.getValue()));

        Map<String, FieldSpec> actorFields = namedTypes.containsKey("actor")
                ? parseFields(namedTypes.get("actor"), Set.of()) : Map.of();

        Map<String, EventTypeSpec> types = new HashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> categories = required(root, "categories").properties().iterator();
             categories.hasNext(); ) {
            Map.Entry<String, JsonNode> category = categories.next();
            JsonNode common = category.getValue().path("common_details");
            for (Iterator<Map.Entry<String, JsonNode>> events = required(category.getValue(), "events").properties().iterator();
                 events.hasNext(); ) {
                Map.Entry<String, JsonNode> event = events.next();
                if (types.put(event.getKey(), parseEvent(event.getKey(), event.getValue(), common)) != null) {
                    throw new IllegalStateException("Event type declared twice: " + event.getKey());
                }
            }
        }
        return new CatalogueVersion(version, types, actorFields);
    }

    private EventTypeSpec parseEvent(String name, JsonNode node, JsonNode common) {
        Set<Result> results = new HashSet<>();
        required(node, "results").forEach(r -> results.add(result(r.asText(), name)));
        Map<String, Result> reasons = new HashMap<>();
        node.path("reasons").properties().forEach(e -> reasons.put(e.getKey(), result(e.getValue().asText(), name)));
        List<String> searchable = new ArrayList<>();
        node.path("searchable").forEach(p -> searchable.add(p.asText()));

        Map<String, JsonNode> declared = new LinkedHashMap<>();
        common.properties().forEach(e -> declared.put(e.getKey(), e.getValue()));
        node.path("details").properties().forEach(e -> declared.put(e.getKey(), e.getValue()));
        Set<String> identifiers = new HashSet<>(CANONICAL);
        identifiers.addAll(declared.keySet());
        Map<String, FieldSpec> details = new LinkedHashMap<>();
        declared.forEach((field, spec) -> details.put(field, parseField(field, spec, identifiers)));
        return new EventTypeSpec(name, required(node, "action").asText(), results, reasons, searchable, details);
    }

    private Map<String, FieldSpec> parseFields(JsonNode node, Set<String> outerIdentifiers) {
        Set<String> identifiers = new HashSet<>(outerIdentifiers);
        identifiers.addAll(CANONICAL);
        node.fieldNames().forEachRemaining(identifiers::add);
        Map<String, FieldSpec> fields = new LinkedHashMap<>();
        node.properties().forEach(e -> fields.put(e.getKey(), parseField(e.getKey(), e.getValue(), identifiers)));
        return fields;
    }

    private FieldSpec parseField(String name, JsonNode node, Set<String> identifiers) {
        RequiredRule required = RequiredRule.parse(rawRequired(node.get("required")), identifiers);
        String type = required(node, "type").asText();
        return typed(name, type, node, required, identifiers);
    }

    private FieldSpec typed(String name, String type, JsonNode node, RequiredRule required, Set<String> identifiers) {
        if (SCALARS.containsKey(type)) {
            Pattern pattern = node.hasNonNull("pattern") ? Pattern.compile(node.get("pattern").asText()) : null;
            return new FieldSpec(name, SCALARS.get(type), required, List.of(), pattern, Map.of(), null);
        }
        switch (type) {
            case "enum" -> {
                List<String> values = new ArrayList<>();
                required(node, "values").forEach(v -> values.add(v.asText()));
                return new FieldSpec(name, FieldType.ENUM, required, values, null, Map.of(), null);
            }
            case "object" -> {
                return new FieldSpec(name, FieldType.FREE_OBJECT, required, List.of(), null, Map.of(), null);
            }
            case "array of string" -> {
                FieldSpec items = new FieldSpec(name, FieldType.STRING, RequiredRule.ALWAYS, List.of(), null,
                        Map.of(), null);
                return new FieldSpec(name, FieldType.ARRAY, required, List.of(), null, Map.of(), items);
            }
            case "array of object" -> {
                FieldSpec items = new FieldSpec(name, FieldType.FREE_OBJECT, RequiredRule.ALWAYS, List.of(), null,
                        Map.of(), null);
                return new FieldSpec(name, FieldType.ARRAY, required, List.of(), null, Map.of(), items);
            }
            case "array" -> {
                JsonNode itemsNode = required(node, "items");
                FieldSpec items = itemsNode.has("type") && itemsNode.get("type").isTextual()
                        ? parseField(name, itemsNode, identifiers).withRequired(RequiredRule.ALWAYS)
                        : new FieldSpec(name, FieldType.OBJECT, RequiredRule.ALWAYS, List.of(), null,
                        parseFields(itemsNode, identifiers), null);
                return new FieldSpec(name, FieldType.ARRAY, required, List.of(), null, Map.of(), items);
            }
            default -> {
                return named(name, type, node, required, identifiers);
            }
        }
    }

    private FieldSpec named(String name, String type, JsonNode node, RequiredRule required, Set<String> identifiers) {
        JsonNode definition = namedTypes.get(type);
        if (definition == null) {
            throw new IllegalStateException("Unknown field type '" + type + "' of field " + name);
        }
        if (definition.has("type") && definition.get("type").isTextual()) {
            // A named scalar ({@code hash}, {@code delivery_method}): its definition plus the field's own required.
            return typed(name, definition.get("type").asText(), definition, required, identifiers);
        }
        return new FieldSpec(name, FieldType.OBJECT, required, List.of(), null, parseFields(definition, identifiers),
                null);
    }

    private static Object rawRequired(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isBoolean() ? node.booleanValue() : node.asText();
    }

    private static Result result(String value, String eventType) {
        return Result.fromWire(value).orElseThrow(() ->
                new IllegalStateException("Unknown result '" + value + "' in " + eventType));
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException("Catalogue: missing '" + field + "'");
        }
        return value;
    }
}
