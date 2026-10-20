package com.eudistack.audit.domain.catalogue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionTest {

    private static final Set<String> IDENTIFIERS = Set.of("result", "reason", "delivery_method", "operation",
            "oidc_client_role");

    private static ConditionScope scope(String... pairs) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        for (int i = 0; i < pairs.length; i += 2) {
            node.put(pairs[i], pairs[i + 1]);
        }
        return new ConditionScope(node, node, node);
    }

    private static Condition parse(String expression) {
        return Condition.parse(expression, IDENTIFIERS).orElseThrow();
    }

    @Test
    void comparison_equalsAndNotEquals() {
        assertThat(parse("result == Success").evaluate(scope("result", "Success"))).isTrue();
        assertThat(parse("result != Success").evaluate(scope("result", "Denied"))).isTrue();
        assertThat(parse("result != Success").evaluate(scope("result", "Success"))).isFalse();
    }

    @Test
    void membership_inAndNotIn() {
        Condition in = parse("delivery_method in [email, ui]");

        assertThat(in.evaluate(scope("delivery_method", "ui"))).isTrue();
        assertThat(in.evaluate(scope("delivery_method", "direct"))).isFalse();
        assertThat(parse("operation not in [Retire]").evaluate(scope("operation", "Create"))).isTrue();
    }

    @Test
    void absentField_differsFromEveryValue() {
        assertThat(parse("reason != OidcClientNotRegistered").evaluate(scope("result", "Success"))).isTrue();
        assertThat(parse("reason == OidcClientNotRegistered").evaluate(scope())).isFalse();
    }

    @Test
    void conjunction_andDisjunction() {
        Condition both = parse("result == Success and delivery_method in [email]");

        assertThat(both.evaluate(scope("result", "Success", "delivery_method", "email"))).isTrue();
        assertThat(both.evaluate(scope("result", "Success", "delivery_method", "ui"))).isFalse();
        assertThat(parse("result == Denied or delivery_method == ui")
                .evaluate(scope("result", "Success", "delivery_method", "ui"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a presentation was received",
            "oidc_client_role == IAM and a presentation was received",
            "unknown_field == X",
            "result >= Success"})
    void notEvaluable_doesNotParse(String expression) {
        assertThat(Condition.parse(expression, IDENTIFIERS)).isEmpty();
    }

    @Test
    void requiredRule_unevaluable_isOptional() {
        RequiredRule rule = RequiredRule.parse("a presentation was received", IDENTIFIERS);

        assertThat(rule).isInstanceOf(RequiredRule.Unevaluable.class);
        assertThat(rule.isRequired(scope())).isFalse();
    }
}
