package com.eudistack.audit.infrastructure.persistence.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eudistack.audit.support.AuditIntegrationTest;
import com.eudistack.audit.support.TestEvents;
import com.eudistack.audit.support.TestTokens;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-01, AC-02, AC-03, AC-04 and AC-18 through the real write endpoint and database.
 */
class RecordEventsIT extends AuditIntegrationTest {

    @Test
    void ac01_validBatchOfTwoTenants_eachEventInItsSchemaWithRecordedAt() throws Exception {
        String correlation = correlation();
        ObjectNode red = TestEvents.issuanceCompleted("red-logistica", correlation, "11111111A");
        ObjectNode sandbox = TestEvents.issuanceCompleted("sandbox", correlation, "22222222B");

        JsonNode response = recordOk(red, sandbox);

        assertThat(response.get("results")).hasSize(2)
                .allSatisfy(r -> assertThat(r.get("status").asText()).isEqualTo("accepted"));
        assertThat(rows("red_logistica", "correlation_id = ?", correlation)).singleElement()
                .satisfies(row -> {
                    assertThat(row.get("event_id")).isEqualTo(red.get("event_id").asText());
                    assertThat(row.get("recorded_at")).isNotNull();
                });
        assertThat(rows("sandbox", "correlation_id = ?", correlation)).hasSize(1);
    }

    @Test
    void ac02_unknownTypeAndUndeclaredField_rejectedAndKeptInPlatform_restStored() throws Exception {
        String correlation = correlation();
        ObjectNode unknownType = TestEvents.issuanceCompleted("red-logistica", correlation, "1A").put("event_type", "Nope");
        ObjectNode undeclared = TestEvents.issuanceCompleted("red-logistica", correlation, "1A");
        ((ObjectNode) undeclared.get("details")).put("holder_phone", "600000000");
        ObjectNode valid = TestEvents.issuanceCompleted("red-logistica", correlation, "1A");

        mvc.perform(write(TestEvents.batch(unknownType, undeclared, valid), TestTokens.writer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("rejected"))
                .andExpect(jsonPath("$.results[0].error.type").value("unknown_event_type"))
                .andExpect(jsonPath("$.results[1].error.type").value("invalid_event"))
                .andExpect(jsonPath("$.results[1].error.status").value(422))
                .andExpect(jsonPath("$.results[1].error.violations[0].field").value("details.holder_phone"))
                .andExpect(jsonPath("$.results[2].status").value("accepted"));

        List<Map<String, Object>> rejected = rows("platform",
                "event_type = 'AuditEventRejected' AND correlation_id = ?", correlation);
        assertThat(rejected).extracting(r -> r.get("reason")).containsExactlyInAnyOrder("UnknownEventType", "InvalidEvent");
        assertThat(rejected).allSatisfy(r -> assertThat((String) r.get("details")).contains("received_event"));
        assertThat(rows("red_logistica", "correlation_id = ?", correlation)).hasSize(1);
    }

    @Test
    void ac03_resentIdentical_isDuplicate_otherContent_isConflict() throws Exception {
        String correlation = correlation();
        ObjectNode event = TestEvents.issuanceCompleted("red-logistica", correlation, "3C");
        recordOk(event);

        JsonNode resent = recordOk(event.deepCopy());
        JsonNode conflict = recordOk(event.deepCopy().put("resource", "issuance:tampered"));

        assertThat(resent.at("/results/0/status").asText()).isEqualTo("duplicate");
        assertThat(conflict.at("/results/0/status").asText()).isEqualTo("rejected");
        assertThat(conflict.at("/results/0/error/type").asText()).isEqualTo("event_id_conflict");
        assertThat(rows("red_logistica", "correlation_id = ?", correlation)).hasSize(1);
        assertThat(rows("platform", "reason = 'EventIdConflict' AND correlation_id = ?", correlation)).hasSize(1);
    }

    @Test
    void ac03_resentInvalidBatch_leavesASingleRejection() throws Exception {
        String correlation = correlation();
        ObjectNode invalid = TestEvents.issuanceCompleted("no-such-tenant", correlation, "4D");

        recordOk(invalid);
        JsonNode again = recordOk(invalid.deepCopy());

        assertThat(again.at("/results/0/error/type").asText()).isEqualTo("unknown_tenant");
        assertThat(rows("platform", "event_type = 'AuditEventRejected' AND correlation_id = ?", correlation)).hasSize(1);
    }

    @Test
    void ac04_unknownTenant_rejected_eventWithoutTenant_storedInPlatform() throws Exception {
        String correlation = correlation();
        ObjectNode unknown = TestEvents.issuanceCompleted("atlantis", correlation, "5E");
        ObjectNode platform = TestEvents.issuanceCompleted("platform", correlation, "5E");

        JsonNode response = recordOk(unknown, platform);

        assertThat(response.at("/results/0/error/type").asText()).isEqualTo("unknown_tenant");
        assertThat(response.at("/results/1/status").asText()).isEqualTo("accepted");
        assertThat(rows("platform", "event_type = 'IssuanceCompleted' AND correlation_id = ?", correlation)).hasSize(1);
        assertThat(rows("platform", "reason = 'UnknownTenant' AND correlation_id = ?", correlation))
                .singleElement().satisfies(r -> assertThat((String) r.get("details")).contains("\"declared_tenant\": \"atlantis\""));
    }

    @Test
    void ac18_eventsOfVersions200And210_acceptedSideBySide() throws Exception {
        String correlation = correlation();
        ObjectNode v200 = TestEvents.issuanceCompleted("red-logistica", correlation, "6F").put("event_version", "2.0.0");
        ObjectNode v210 = TestEvents.issuanceCompleted("sandbox", correlation, "6F").put("event_version", "2.1.0");

        JsonNode response = recordOk(v200, v210);

        assertThat(response.get("results")).allSatisfy(r -> assertThat(r.get("status").asText()).isEqualTo("accepted"));
    }

    @Test
    void nulCharacter_isRejectedAsInvalid_andDoesNotPoisonTheBatch() throws Exception {
        // PostgreSQL text and jsonb cannot hold U+0000: storing it would fail the whole batch with a 503
        // that the producer retries forever.
        String correlation = correlation();
        ObjectNode withNul = TestEvents.issuanceCompleted("red-logistica", correlation, "7G");
        withNul.put("resource", "issuance:\u0000x");
        ObjectNode valid = TestEvents.issuanceCompleted("red-logistica", correlation, "7G");

        JsonNode response = recordOk(withNul, valid);

        assertThat(response.at("/results/0/status").asText()).isEqualTo("rejected");
        assertThat(response.at("/results/0/error/type").asText()).isEqualTo("invalid_event");
        assertThat(response.at("/results/1/status").asText()).isEqualTo("accepted");
        assertThat(rows("platform", "reason = 'InvalidEvent' AND correlation_id = ?", correlation)).hasSize(1);
        assertThat(rows("red_logistica", "correlation_id = ?", correlation)).hasSize(1);
    }

    @Test
    void malformedBatch_is400AndStoresNothing() throws Exception {
        mvc.perform(write(TestEvents.MAPPER.createObjectNode().put("events", "x"), TestTokens.writer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("BAD_REQUEST"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(WRITE_HOST + "/api/v1/events").header("Authorization", "Bearer " + TestTokens.writer())
                        .contentType("application/json").content("{\"events\": [ {\"a\":1}, "))
                .andExpect(status().isBadRequest());
    }
}
