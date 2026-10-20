package com.eudistack.audit.infrastructure.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eudistack.audit.support.AuditIntegrationTest;
import com.eudistack.audit.support.TestEvents;
import com.eudistack.audit.support.TestTokens;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-06, AC-09, AC-11, AC-13, AC-14, AC-15 and pagination through the real query endpoint.
 */
class ListEventsIT extends AuditIntegrationTest {

    private static String since(Duration ago) {
        // RFC 3339 in UTC has no character that needs escaping in a query string.
        return Instant.now().minus(ago).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private JsonNode list(String host, String query, String token) throws Exception {
        return json(mvc.perform(query(host, "/audit/api/v1/events?" + query, token))
                .andExpect(status().isOk()).andReturn());
    }

    @Test
    void ac06_listByCorrelationId_returnsEveryEventOfTheOperation() throws Exception {
        String correlation = correlation();
        recordOk(TestEvents.issuanceCompleted("red-logistica", correlation, "1A"),
                TestEvents.presentationVerification("red-logistica", correlation, "1A"));

        JsonNode page = list(RED_HOST, "correlation_id=" + correlation, TestTokens.tenantAuditor("red-logistica"));

        assertThat(page.get("events")).extracting(e -> e.get("event_type").asText())
                .containsExactlyInAnyOrder("IssuanceCompleted", "PresentationVerification");
        assertThat(page.get("events")).allSatisfy(e -> assertThat(e.has("recorded_at")).isTrue());
        assertThat(page.get("next_cursor").isNull()).isTrue();
    }

    @Test
    void ac09_organizationReader_wholeTenantInOrder_domainReader_ownOrganizationOnly() throws Exception {
        String correlation = correlation();
        ObjectNode first = TestEvents.withOrganization(
                TestEvents.issuanceCompleted("red-logistica", correlation, "2B"), "VATES-TRANSPORTES-SUR");
        first.put("occurred_at", Instant.now().minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS).toString());
        ObjectNode second = TestEvents.withOrganization(
                TestEvents.issuanceCompleted("red-logistica", correlation, "2B"), "VATES-OTRA");
        ObjectNode otherTenant = TestEvents.issuanceCompleted("sandbox", correlation, "2B");
        recordOk(second, first, otherTenant);

        JsonNode whole = list(RED_HOST, "correlation_id=" + correlation, TestTokens.tenantAuditor("red-logistica"));
        JsonNode domain = list(RED_HOST, "correlation_id=" + correlation,
                TestTokens.domainAuditor("red-logistica", "VATES-TRANSPORTES-SUR"));

        assertThat(whole.get("events")).extracting(e -> e.get("organization").asText())
                .containsExactly("VATES-TRANSPORTES-SUR", "VATES-OTRA");
        assertThat(whole.get("events")).allSatisfy(e -> assertThat(e.get("tenant").asText()).isEqualTo("red-logistica"));
        assertThat(domain.get("events")).extracting(e -> e.get("organization").asText())
                .containsExactly("VATES-TRANSPORTES-SUR");
    }

    @Test
    void ac09_domainReader_cannotWidenWithTheOrganizationParameter() throws Exception {
        String correlation = correlation();
        recordOk(TestEvents.withOrganization(TestEvents.issuanceCompleted("red-logistica", correlation, "2C"), "VATES-OTRA"));

        JsonNode page = list(RED_HOST, "correlation_id=" + correlation + "&organization=VATES-OTRA",
                TestTokens.domainAuditor("red-logistica", "VATES-TRANSPORTES-SUR"));

        assertThat(page.get("events")).isEmpty();
    }

    @Test
    void ac11_administratorAll_seesEveryTenantAndPlatform_auditedInPlatform() throws Exception {
        String correlation = correlation();
        recordOk(TestEvents.issuanceCompleted("red-logistica", correlation, "3C"),
                TestEvents.issuanceCompleted("sandbox", correlation, "3C"),
                TestEvents.issuanceCompleted("platform", correlation, "3C"));

        JsonNode page = list(PLATFORM_HOST, "tenant=all&correlation_id=" + correlation, TestTokens.administrator());

        assertThat(page.get("events")).extracting(e -> e.get("tenant").asText())
                .containsExactlyInAnyOrder("red-logistica", "sandbox", "platform");
        assertThat(rows("platform", "event_type = 'AuditTrailQueried' AND details->'filters'->>'correlation_id' = ?",
                correlation)).singleElement()
                .satisfies(r -> assertThat((String) r.get("details")).contains("\"queried_scope\": \"all\""));
    }

    @Test
    void ac13_searchByHolderIdentifier_returnsOnlyThatPerson() throws Exception {
        String holder = "H" + System.nanoTime();
        String correlation = correlation();
        recordOk(TestEvents.issuanceCompleted("red-logistica", correlation, holder),
                TestEvents.presentationVerification("red-logistica", correlation, holder),
                TestEvents.issuanceCompleted("red-logistica", correlation, "SOMEONE-ELSE"));

        JsonNode page = list(RED_HOST, "event_type=IssuanceCompleted,PresentationVerification"
                + "&details_path=holder.identifier&details_value=" + holder + "&from=" + since(Duration.ofDays(1)),
                TestTokens.tenantAuditor("red-logistica"));

        assertThat(page.get("events")).hasSize(2)
                .allSatisfy(e -> assertThat(e.at("/details/holder/identifier").asText()).isEqualTo(holder));
    }

    @Test
    void ac14_pathNotSearchable_andIncompletePathSearch() throws Exception {
        String auditor = TestTokens.tenantAuditor("red-logistica");

        mvc.perform(query(RED_HOST, "/audit/api/v1/events?event_type=IssuanceCompleted&details_path=valid_from"
                        + "&details_value=x&from=" + since(Duration.ofDays(1)), auditor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("path_not_searchable"));
        mvc.perform(query(RED_HOST, "/audit/api/v1/events?details_path=holder.identifier&details_value=x&from="
                        + since(Duration.ofDays(1)), auditor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("BAD_REQUEST"));
        mvc.perform(query(RED_HOST, "/audit/api/v1/events?event_type=IssuanceCompleted&details_path=holder.identifier"
                        + "&details_value=x", auditor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("BAD_REQUEST"));
    }

    @Test
    void ac15_periodOver31Days_isBadRequest_periodHundredDaysAgo_isArchived() throws Exception {
        String auditor = TestTokens.tenantAuditor("red-logistica");

        mvc.perform(query(RED_HOST, "/audit/api/v1/events?from=" + since(Duration.ofDays(40)), auditor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("BAD_REQUEST"));
        mvc.perform(query(RED_HOST, "/audit/api/v1/events?from=" + since(Duration.ofDays(100)) + "&to="
                        + since(Duration.ofDays(99)), auditor))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("period_archived"));
    }

    @Test
    void unknownEventType_isUnknownEventType() throws Exception {
        mvc.perform(query(RED_HOST, "/audit/api/v1/events?event_type=Teleported", TestTokens.tenantAuditor("red-logistica")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("unknown_event_type"));
    }

    @Test
    void emptyResult_isAnEmptyList() throws Exception {
        JsonNode page = list(RED_HOST, "correlation_id=" + correlation(), TestTokens.tenantAuditor("red-logistica"));

        assertThat(page.get("events")).isEmpty();
    }

    @Test
    void pagination_walksEveryEventOnceInOrder() throws Exception {
        String correlation = correlation();
        List<ObjectNode> events = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ObjectNode event = TestEvents.issuanceCompleted("sandbox", correlation, "P" + i);
            event.put("occurred_at", Instant.now().minus(Duration.ofMinutes(50 - i)).truncatedTo(ChronoUnit.SECONDS).toString());
            events.add(event);
        }
        recordOk(events.toArray(ObjectNode[]::new));
        String auditor = TestTokens.tenantAuditor("sandbox");

        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = list(SANDBOX_HOST, "limit=2&correlation_id=" + correlation
                    + (cursor == null ? "" : "&cursor=" + cursor), auditor);
            page.get("events").forEach(e -> seen.add(e.get("event_id").asText()));
            cursor = page.get("next_cursor").isNull() ? null : page.get("next_cursor").asText();
        } while (cursor != null);

        assertThat(seen).containsExactlyElementsOf(events.stream().map(e -> e.get("event_id").asText()).toList());
    }

    @Test
    void cursorOfAnotherQuery_isBadRequest() throws Exception {
        String correlation = correlation();
        recordOk(TestEvents.issuanceCompleted("sandbox", correlation, "Q1"),
                TestEvents.issuanceCompleted("sandbox", correlation, "Q2"));
        String auditor = TestTokens.tenantAuditor("sandbox");
        String cursor = list(SANDBOX_HOST, "limit=1&correlation_id=" + correlation, auditor).get("next_cursor").asText();

        mvc.perform(query(SANDBOX_HOST, "/audit/api/v1/events?limit=1&correlation_id=other&cursor=" + cursor, auditor))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nulCharacterInAParameter_isBadRequest_notUnavailable() throws Exception {
        String auditor = TestTokens.tenantAuditor("sandbox");

        // Set as parameters: MockMvc would encode the '%' of a literal "%00" in the URL once more.
        mvc.perform(query(SANDBOX_HOST, "/audit/api/v1/events", auditor).param("correlation_id", "a\u0000b"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("BAD_REQUEST"));
        mvc.perform(query(PLATFORM_HOST, "/audit/api/v1/events", TestTokens.administrator()).param("tenant", "\u0000"))
                .andExpect(status().isBadRequest());
    }
}
