package com.eudistack.audit.domain.catalogue;

import java.util.Set;

/**
 * When a field is required: always, never, or only when a condition holds.
 *
 * <p>The catalogue writes conditions as text. Those that can be evaluated against the event
 * ({@code result != Success}, {@code delivery_method in [email, ui]}) become {@link When}. Those
 * written in natural language ({@code a presentation was received}) cannot, and the field is treated
 * as optional (decision D-4 in {@code docs/architecture.md}).
 */
public sealed interface RequiredRule {

    boolean isRequired(ConditionScope scope);

    RequiredRule ALWAYS = new Always();
    RequiredRule OPTIONAL = new Optional();

    /**
     * Parses the {@code required} value of the catalogue: {@code true}, {@code false} or a condition.
     *
     * @param raw         the YAML value ({@link Boolean}, {@link String} or {@code null})
     * @param identifiers names a condition may refer to (sibling fields, {@code details} fields and the canonical record)
     */
    static RequiredRule parse(Object raw, Set<String> identifiers) {
        if (raw == null || Boolean.FALSE.equals(raw)) {
            return OPTIONAL;
        }
        if (Boolean.TRUE.equals(raw)) {
            return ALWAYS;
        }
        String text = raw.toString().trim();
        if ("true".equalsIgnoreCase(text)) {
            return ALWAYS;
        }
        if ("false".equalsIgnoreCase(text)) {
            return OPTIONAL;
        }
        return Condition.parse(text, identifiers)
                .<RequiredRule>map(condition -> new When(condition, text))
                .orElse(new Unevaluable(text));
    }

    record Always() implements RequiredRule {
        @Override
        public boolean isRequired(ConditionScope scope) {
            return true;
        }
    }

    record Optional() implements RequiredRule {
        @Override
        public boolean isRequired(ConditionScope scope) {
            return false;
        }
    }

    record When(Condition condition, String expression) implements RequiredRule {
        @Override
        public boolean isRequired(ConditionScope scope) {
            return condition.evaluate(scope);
        }
    }

    /**
     * A condition in natural language: the field is optional.
     */
    record Unevaluable(String expression) implements RequiredRule {
        @Override
        public boolean isRequired(ConditionScope scope) {
            return false;
        }
    }
}
