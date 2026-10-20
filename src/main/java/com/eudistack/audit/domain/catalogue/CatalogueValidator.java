package com.eudistack.audit.domain.catalogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.RejectionReason;
import com.eudistack.audit.domain.model.Result;
import com.eudistack.audit.domain.model.Violation;
import com.eudistack.audit.domain.policy.NulCharacters;
import com.eudistack.audit.domain.port.out.CatalogueResolverPort;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates a received event against the catalogue snapshot of its {@code event_version} (FR-04, FR-19).
 *
 * <p>Allow-list: the canonical record accepts only its fields, {@code details} only the fields its
 * type declares, and {@code result} / {@code reason} only the vocabulary of the type. That is what keeps
 * secrets, tokens and attestation content out of an event (AC-17): no field can carry them.
 */
public final class CatalogueValidator {

    static final Set<String> CANONICAL_FIELDS = Set.of(
            "event_id", "event_type", "event_version", "occurred_at", "tenant", "organization", "source",
            "actor", "action", "resource", "result", "reason", "correlation_id", "details");

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern SEMVER_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");
    /** RFC 3339 {@code full-date}: a four-digit year, which also keeps the value inside timestamptz. */
    private static final Pattern RFC3339_DATE_PREFIX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[Tt]");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final CatalogueResolverPort catalogue;

    public CatalogueValidator(CatalogueResolverPort catalogue) {
        this.catalogue = catalogue;
    }

    public ValidationResult validate(JsonNode event) {
        if (event == null || !event.isObject()) {
            return invalid(new Violation("", "The event must be a JSON object"));
        }
        Optional<String> nul = NulCharacters.firstPath(event);
        if (nul.isPresent()) {
            return invalid(new Violation(nul.get(), "Must not contain the character U+0000"));
        }
        String eventType = text(event, "event_type");
        String eventVersion = text(event, "event_version");
        if (eventType == null || eventType.isBlank()) {
            return invalid(new Violation("event_type", "Field is required and must be a string"));
        }
        if (eventVersion == null || !SEMVER_PATTERN.matcher(eventVersion).matches()) {
            return invalid(new Violation("event_version", "Field is required and must be a semantic version"));
        }
        Optional<EventTypeSpec> spec = catalogue.version(eventVersion).flatMap(v -> v.type(eventType));
        if (spec.isEmpty()) {
            return new ValidationResult.Invalid(RejectionReason.UNKNOWN_EVENT_TYPE, List.of(new Violation(
                    "event_type", eventType + " " + eventVersion + " is not in the catalogue")));
        }
        CatalogueVersion version = catalogue.version(eventVersion).orElseThrow();
        List<Violation> violations = new ArrayList<>();
        validateCanonical(event, spec.get(), version, violations);
        if (!violations.isEmpty()) {
            return new ValidationResult.Invalid(RejectionReason.INVALID_EVENT, violations);
        }
        return new ValidationResult.Valid(AuditEvent.fromValidatedJson(event), event);
    }

    private void validateCanonical(JsonNode event, EventTypeSpec spec, CatalogueVersion version,
                                   List<Violation> violations) {
        String declaredBy = spec.name() + " " + version.version();
        for (Iterator<String> it = event.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if ("recorded_at".equals(field)) {
                violations.add(new Violation(field, "Set by the audit API, never sent"));
            } else if (!CANONICAL_FIELDS.contains(field)) {
                violations.add(new Violation(field, "Field not declared by the canonical record"));
            }
        }
        requirePattern(event, "event_id", UUID_PATTERN, "must be a UUID", violations);
        requireDateTime(event, "occurred_at", violations);
        requireText(event, "tenant", violations);
        optionalText(event, "organization", violations);
        requireText(event, "source", violations);
        requireText(event, "resource", violations);
        requireText(event, "correlation_id", violations);

        JsonNode actor = event.get("actor");
        if (actor == null) {
            violations.add(new Violation("actor", "Field is required"));
        } else if (!actor.isObject()) {
            violations.add(new Violation("actor", "Field must be an object"));
        } else {
            validateObject("actor", actor, version.actorFields(), new ConditionScope(actor, null, event),
                    "the actor type", violations);
        }

        String action = text(event, "action");
        if (action == null) {
            violations.add(new Violation("action", "Field is required and must be a string"));
        } else if (!spec.action().equals(action)) {
            violations.add(new Violation("action", spec.name() + " only allows the action " + spec.action()));
        }

        validateResultAndReason(event, spec, violations);

        JsonNode details = event.get("details");
        if (details == null) {
            violations.add(new Violation("details", "Field is required"));
        } else if (!details.isObject()) {
            violations.add(new Violation("details", "Field must be an object"));
        } else {
            validateObject("details", details, spec.details(), new ConditionScope(details, details, event),
                    declaredBy, violations);
        }
    }

    private static void validateResultAndReason(JsonNode event, EventTypeSpec spec, List<Violation> violations) {
        String resultText = text(event, "result");
        Optional<Result> result = Result.fromWire(resultText);
        if (result.isEmpty()) {
            violations.add(new Violation("result", "Field is required and must be Success, Denied or Failure"));
            return;
        }
        if (!spec.results().contains(result.get())) {
            violations.add(new Violation("result", spec.name() + " does not allow the result " + resultText));
        }
        JsonNode reasonNode = event.get("reason");
        if (result.get() == Result.SUCCESS) {
            if (reasonNode != null) {
                violations.add(new Violation("reason", "Only allowed when result is not Success"));
            }
            return;
        }
        if (reasonNode == null || !reasonNode.isTextual()) {
            violations.add(new Violation("reason", "Required when result is not Success"));
            return;
        }
        Result determined = spec.reasons().get(reasonNode.asText());
        if (determined == null) {
            violations.add(new Violation("reason", reasonNode.asText() + " is not a reason of " + spec.name()));
        } else if (determined != result.get()) {
            violations.add(new Violation("reason", reasonNode.asText() + " determines the result "
                    + determined.wireValue()));
        }
    }

    private void validateObject(String path, JsonNode node, Map<String, FieldSpec> fields, ConditionScope scope,
                                String declaredBy, List<Violation> violations) {
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!fields.containsKey(field)) {
                violations.add(new Violation(path + "." + field, "Field not declared by " + declaredBy));
            }
        }
        for (FieldSpec field : fields.values()) {
            JsonNode value = node.get(field.name());
            String fieldPath = path + "." + field.name();
            if (value == null) {
                if (field.required().isRequired(scope)) {
                    violations.add(new Violation(fieldPath, "Field required by " + declaredBy));
                }
            } else {
                validateValue(fieldPath, field, value, scope, declaredBy, violations);
            }
        }
    }

    private void validateValue(String path, FieldSpec field, JsonNode value, ConditionScope scope,
                               String declaredBy, List<Violation> violations) {
        String error = switch (field.type()) {
            case STRING -> value.isTextual() ? matchPattern(field, value) : "must be a string";
            case BOOLEAN -> value.isBoolean() ? null : "must be a boolean";
            case INTEGER -> value.isIntegralNumber() ? null : "must be an integer";
            case DATE_TIME -> value.isTextual() && isDateTime(value.asText()) ? null : "must be an RFC 3339 date-time";
            case URI -> value.isTextual() && isAbsoluteUri(value.asText()) ? null : "must be an absolute URI";
            case EMAIL -> value.isTextual() && EMAIL_PATTERN.matcher(value.asText()).matches() ? null : "must be an email";
            case UUID -> value.isTextual() && UUID_PATTERN.matcher(value.asText()).matches() ? null : "must be a UUID";
            case SEMVER -> value.isTextual() && SEMVER_PATTERN.matcher(value.asText()).matches()
                    ? null : "must be a semantic version";
            case ENUM -> value.isTextual() && field.values().contains(value.asText())
                    ? null : "must be one of " + field.values();
            case FREE_OBJECT -> value.isObject() ? null : "must be an object";
            case OBJECT -> {
                if (!value.isObject()) {
                    yield "must be an object";
                }
                validateObject(path, value, field.fields(), scope.nested(value), declaredBy, violations);
                yield null;
            }
            case ARRAY -> {
                if (!value.isArray()) {
                    yield "must be an array";
                }
                for (int i = 0; i < value.size(); i++) {
                    validateValue(path + "[" + i + "]", field.items(), value.get(i), scope, declaredBy, violations);
                }
                yield null;
            }
        };
        if (error != null) {
            violations.add(new Violation(path, "Field " + error));
        }
    }

    private static String matchPattern(FieldSpec field, JsonNode value) {
        if (field.pattern() == null || field.pattern().matcher(value.asText()).matches()) {
            return null;
        }
        return "must match " + field.pattern().pattern();
    }

    private static void requireText(JsonNode event, String field, List<Violation> violations) {
        JsonNode value = event.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            violations.add(new Violation(field, "Field is required and must be a non-empty string"));
        }
    }

    private static void optionalText(JsonNode event, String field, List<Violation> violations) {
        JsonNode value = event.get(field);
        if (value != null && (!value.isTextual() || value.asText().isBlank())) {
            violations.add(new Violation(field, "Field must be a non-empty string"));
        }
    }

    private static void requirePattern(JsonNode event, String field, Pattern pattern, String message,
                                       List<Violation> violations) {
        JsonNode value = event.get(field);
        if (value == null || !value.isTextual() || !pattern.matcher(value.asText()).matches()) {
            violations.add(new Violation(field, "Field is required and " + message));
        }
    }

    private static void requireDateTime(JsonNode event, String field, List<Violation> violations) {
        JsonNode value = event.get(field);
        if (value == null || !value.isTextual() || !isDateTime(value.asText())) {
            violations.add(new Violation(field, "Field is required and must be an RFC 3339 date-time"));
        }
    }

    private static boolean isDateTime(String text) {
        if (!RFC3339_DATE_PREFIX.matcher(text).find()) {
            return false;
        }
        try {
            OffsetDateTime.parse(text);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isAbsoluteUri(String text) {
        try {
            return URI.create(text).isAbsolute();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static ValidationResult invalid(Violation violation) {
        return new ValidationResult.Invalid(RejectionReason.INVALID_EVENT, List.of(violation));
    }
}
