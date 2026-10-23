package com.eudistack.audit.domain.catalogue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluable {@code required} condition of the catalogue.
 *
 * <p>Grammar: clauses joined by {@code and} / {@code or} ({@code and} binds tighter), where a clause
 * is {@code <field> == <value>}, {@code <field> != <value>}, {@code <field> in [v1, v2]} or
 * {@code <field> not in [v1, v2]}. An absent field is different from every value. Anything else —
 * natural language, an unknown field — does not parse, and the caller treats the field as optional.
 */
public final class Condition {

    private static final Pattern COMPARISON = Pattern.compile(
            "^([a-z_][a-z0-9_]*)\\s*(==|!=)\\s*([A-Za-z0-9_.:\\-]+)$");
    private static final Pattern MEMBERSHIP = Pattern.compile(
            "^([a-z_][a-z0-9_]*+)\\s++(not\\s++in|in)\\s*+\\[\\s*+([A-Za-z0-9_.:\\-]++(?:\\s*+,\\s*+[A-Za-z0-9_.:\\-]++)*+)\\s*+]$");
    private static final Pattern OR = Pattern.compile("\\s++or\\s++");
    private static final Pattern AND = Pattern.compile("\\s++and\\s++");

    /** Disjunction of conjunctions of clauses. */
    private final List<List<Clause>> alternatives;

    private Condition(List<List<Clause>> alternatives) {
        this.alternatives = alternatives;
    }

    public static Optional<Condition> parse(String expression, Set<String> identifiers) {
        List<List<Clause>> alternatives = new ArrayList<>();
        for (String alternative : OR.split(expression.trim())) {
            List<Clause> clauses = new ArrayList<>();
            for (String text : AND.split(alternative.trim())) {
                Optional<Clause> clause = parseClause(text.trim(), identifiers);
                if (clause.isEmpty()) {
                    return Optional.empty();
                }
                clauses.add(clause.get());
            }
            alternatives.add(List.copyOf(clauses));
        }
        return Optional.of(new Condition(List.copyOf(alternatives)));
    }

    public boolean evaluate(ConditionScope scope) {
        return alternatives.stream().anyMatch(all -> all.stream().allMatch(clause -> clause.test(scope)));
    }

    private static Optional<Clause> parseClause(String text, Set<String> identifiers) {
        Matcher comparison = COMPARISON.matcher(text);
        if (comparison.matches() && identifiers.contains(comparison.group(1))) {
            String field = comparison.group(1);
            boolean negated = "!=".equals(comparison.group(2));
            List<String> values = List.of(comparison.group(3));
            return Optional.of(new Clause(field, negated, values));
        }
        Matcher membership = MEMBERSHIP.matcher(text);
        if (membership.matches() && identifiers.contains(membership.group(1))) {
            String field = membership.group(1);
            boolean negated = membership.group(2).startsWith("not");
            List<String> values = Arrays.stream(membership.group(3).split(",")).map(String::trim).toList();
            return Optional.of(new Clause(field, negated, values));
        }
        return Optional.empty();
    }

    private record Clause(String field, boolean negated, List<String> values) {

        boolean test(ConditionScope scope) {
            String value = scope.valueOf(field);
            boolean member = value != null && values.contains(value);
            return negated != member;
        }
    }
}
