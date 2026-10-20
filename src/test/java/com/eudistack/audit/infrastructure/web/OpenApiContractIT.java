package com.eudistack.audit.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.eudistack.audit.support.AuditIntegrationTest;
import com.eudistack.audit.support.TestEvents;
import com.eudistack.audit.support.TestTokens;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-01: the responses of the service conform to {@code docs/openapi.yaml} (copy of
 * {@code annex/openapi.yaml} of EUD-244), validated as JSON Schema 2020-12.
 */
class OpenApiContractIT extends AuditIntegrationTest {

    private static JsonNode openApi;

    @BeforeAll
    static void load() throws Exception {
        openApi = new ObjectMapper(new YAMLFactory()).readTree(Path.of("docs/openapi.yaml").toFile());
    }

    private static Set<ValidationMessage> validate(String component, JsonNode instance) {
        ObjectNode document = openApi.deepCopy();
        document.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        document.put("$ref", "#/components/schemas/" + component);
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(document);
        return schema.validate(instance);
    }

    /**
     * {@code AuditEvent} is {@code allOf: [AuditEventInput, {recorded_at}]} while {@code AuditEventInput}
     * declares {@code additionalProperties: false}: in strict JSON Schema no instance with
     * {@code recorded_at} satisfies both. The event is checked against {@code AuditEventInput} without
     * {@code recorded_at}, and {@code recorded_at} on its own (contract finding reported to the PM).
     */
    private static void assertAuditEvent(JsonNode event) {
        assertThat(event.get("recorded_at")).as("recorded_at").isNotNull();
        ObjectNode input = event.deepCopy();
        input.remove("recorded_at");
        assertThat(validate("AuditEventInput", input)).as(event.toString()).isEmpty();
    }

    @Test
    void operations_ofTheContract_exist() {
        assertThat(openApi.at("/paths/~1api~1v1~1events/post/operationId").asText()).isEqualTo("recordEvents");
        assertThat(openApi.at("/paths/~1api~1v1~1events/get/operationId").asText()).isEqualTo("listEvents");
        assertThat(openApi.at("/paths/~1api~1v1~1events~1{event_id}/get/operationId").asText()).isEqualTo("getEvent");
        assertThat(openApi.at("/paths/~1api~1v1~1events").has("put")).isFalse();
        assertThat(openApi.at("/paths/~1api~1v1~1events~1{event_id}").has("delete")).isFalse();
    }

    @Test
    void recordEvents_response_conforms() throws Exception {
        ObjectNode valid = TestEvents.issuanceCompleted("sandbox", correlation(), "1");
        ObjectNode invalid = TestEvents.issuanceCompleted("sandbox", correlation(), "1");
        ((ObjectNode) invalid.get("details")).put("holder_phone", "600");

        JsonNode response = recordOk(valid, valid.deepCopy(), invalid);

        assertThat(validate("EventBatchResult", response)).isEmpty();
        assertThat(validate("Problem", response.at("/results/2/error"))).isEmpty();
    }

    @Test
    void listEvents_and_getEvent_responses_conform() throws Exception {
        String correlation = correlation();
        ObjectNode event = TestEvents.presentationVerification("red-logistica", correlation, "1");
        recordOk(event, TestEvents.issuanceCompleted("red-logistica", correlation, "1"));
        String token = TestTokens.tenantAuditor("red-logistica");

        JsonNode list = json(mvc.perform(query(RED_HOST, "/audit/api/v1/events?limit=1&correlation_id=" + correlation,
                token)).andReturn());
        JsonNode single = json(mvc.perform(query(RED_HOST, "/audit/api/v1/events/" + event.get("event_id").asText(),
                token)).andReturn());

        assertThat(list.get("next_cursor").isTextual()).isTrue();
        list.get("events").forEach(OpenApiContractIT::assertAuditEvent);
        assertAuditEvent(single);
        assertThat(single.get("details")).isEqualTo(event.get("details"));
    }

    @Test
    void problems_conform() throws Exception {
        String token = TestTokens.tenantAuditor("red-logistica");
        MvcResult[] results = {
                mvc.perform(query(RED_HOST, "/audit/api/v1/events?limit=9999", token)).andReturn(),
                mvc.perform(query(RED_HOST, "/audit/api/v1/events/" + UUID.randomUUID(), token)).andReturn(),
                mvc.perform(query(RED_HOST, "/audit/api/v1/events", null)).andReturn(),
                mvc.perform(query(RED_HOST, "/audit/api/v1/events?from=2001-01-01T00:00:00Z&to=2001-01-02T00:00:00Z",
                        token)).andReturn(),
                mvc.perform(write(TestEvents.MAPPER.createObjectNode(), TestTokens.writer())).andReturn()};

        for (MvcResult result : results) {
            assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
            JsonNode problem = json(result);
            assertThat(validate("Problem", problem)).as(problem.toString()).isEmpty();
            assertThat(problem.get("status").asInt()).isEqualTo(result.getResponse().getStatus());
        }
    }
}
