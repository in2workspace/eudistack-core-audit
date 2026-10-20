package com.eudistack.audit.infrastructure.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eudistack.audit.support.AuditIntegrationTest;
import com.eudistack.audit.support.TestEvents;
import com.eudistack.audit.support.TestTokens;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-07, AC-08, AC-10 and AC-12, plus the host policy and the no-store header.
 */
class AccessControlIT extends AuditIntegrationTest {

    @Test
    void ac07_readTokenWriting_isDenied_andLeavesAuditWriteDenied() throws Exception {
        int size = 3 + (int) (System.nanoTime() % 50);
        ObjectNode[] events = new ObjectNode[size];
        for (int i = 0; i < size; i++) {
            events[i] = TestEvents.issuanceCompleted("red-logistica", correlation(), "1A");
        }

        mvc.perform(write(TestEvents.batch(events), TestTokens.tenantAuditor("red-logistica")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("insufficient_permission"));

        assertThat(rows("platform", "event_type = 'AuditWriteDenied' AND reason = 'PermissionMissing' "
                + "AND (details->>'event_count')::int = ?", size)).isNotEmpty();
    }

    @Test
    void ac07_writeTokenQuerying_isDenied() throws Exception {
        mvc.perform(query(RED_HOST, "/audit/api/v1/events", TestTokens.writer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeWithoutToken_is401_andLeavesAuditWriteDeniedInvalidToken() throws Exception {
        long before = rows("platform", "event_type = 'AuditWriteDenied' AND reason = 'InvalidToken'").size();

        mvc.perform(write(TestEvents.batch(TestEvents.issuanceCompleted("sandbox", correlation(), "1")), null))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(jsonPath("$.type").value("authentication_error"));
        mvc.perform(write(TestEvents.batch(TestEvents.issuanceCompleted("sandbox", correlation(), "1")), "garbage"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("invalid_token"));

        assertThat(rows("platform", "event_type = 'AuditWriteDenied' AND reason = 'InvalidToken'"))
                .hasSizeGreaterThanOrEqualTo((int) before + 2);
    }

    @Test
    void ac08_noOperationModifiesOrDeletesAnEvent() throws Exception {
        String id = UUID.randomUUID().toString();
        String writer = "Bearer " + TestTokens.writer();
        String reader = "Bearer " + TestTokens.tenantAuditor("red-logistica");

        mvc.perform(put(RED_HOST + "/audit/api/v1/events/" + id).header("Authorization", reader))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete(RED_HOST + "/audit/api/v1/events/" + id).header("Authorization", reader))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(patch(RED_HOST + "/audit/api/v1/events/" + id).header("Authorization", reader))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete(WRITE_HOST + "/api/v1/events/" + id).header("Authorization", writer))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(put(WRITE_HOST + "/api/v1/events").header("Authorization", writer))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"));
    }

    @Test
    void hostPolicy_facesNeverCross() throws Exception {
        mvc.perform(post(RED_HOST + "/audit/api/v1/events").header("Authorization", "Bearer " + TestTokens.writer())
                        .contentType("application/json").content("{\"events\":[]}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.type").value("BAD_REQUEST"));
        mvc.perform(get(WRITE_HOST + "/api/v1/events").header("Authorization", "Bearer " + TestTokens.administrator()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void browserOriginOnTheInternalHost_is403WithoutEvent() throws Exception {
        String correlation = correlation();

        mvc.perform(write(TestEvents.batch(TestEvents.issuanceCompleted("sandbox", correlation, "1")), TestTokens.writer())
                        .header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("insufficient_permission"));

        assertThat(rows("sandbox", "correlation_id = ?", correlation)).isEmpty();
    }

    @Test
    void ac10_eventOfAnotherTenant_isNotFound_likeOneThatDoesNotExist() throws Exception {
        ObjectNode sandboxEvent = TestEvents.issuanceCompleted("sandbox", correlation(), "1");
        recordOk(sandboxEvent);
        String auditor = TestTokens.tenantAuditor("red-logistica");

        String otherTenant = mvc.perform(query(RED_HOST, "/audit/api/v1/events/" + sandboxEvent.get("event_id").asText(),
                auditor)).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String missing = mvc.perform(query(RED_HOST, "/audit/api/v1/events/" + UUID.randomUUID(), auditor))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        JsonNode a = TestEvents.MAPPER.readTree(otherTenant);
        JsonNode b = TestEvents.MAPPER.readTree(missing);
        assertThat(a.get("type").asText()).isEqualTo("event_not_found");
        assertThat(a.get("detail")).isEqualTo(b.get("detail"));
        assertThat(a.get("title")).isEqualTo(b.get("title"));
    }

    @Test
    void ac10_eventOfAnotherOrganization_isNotFoundForADomainReader() throws Exception {
        ObjectNode event = TestEvents.withOrganization(TestEvents.issuanceCompleted("red-logistica", correlation(), "1"),
                "VATES-OTRA");
        recordOk(event);

        mvc.perform(query(RED_HOST, "/audit/api/v1/events/" + event.get("event_id").asText(),
                        TestTokens.domainAuditor("red-logistica", "VATES-TRANSPORTES-SUR")))
                .andExpect(status().isNotFound());
        mvc.perform(query(RED_HOST, "/audit/api/v1/events/" + event.get("event_id").asText(),
                        TestTokens.tenantAuditor("red-logistica")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event_id").value(event.get("event_id").asText()))
                .andExpect(jsonPath("$.recorded_at").exists());
    }

    @Test
    void ac12_everyQueryGrantedOrDenied_leavesAuditTrailQueried() throws Exception {
        String marker = correlation();
        String auditor = TestTokens.tenantAuditor("red-logistica");

        mvc.perform(query(RED_HOST, "/audit/api/v1/events?correlation_id=" + marker, auditor))
                .andExpect(status().isOk());
        mvc.perform(query(RED_HOST, "/audit/api/v1/events?correlation_id=" + marker, TestTokens.tenantAuditor("sandbox")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("tenant_mismatch"));
        mvc.perform(query(RED_HOST, "/audit/api/v1/events?tenant=all&correlation_id=" + marker, auditor))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("tenant_mismatch"));

        List<Map<String, Object>> trail = rows("red_logistica",
                "event_type = 'AuditTrailQueried' AND details->'filters'->>'correlation_id' = ?", marker);
        assertThat(trail).hasSize(3);
        assertThat(trail).extracting(r -> r.get("reason")).containsExactlyInAnyOrder(null, "TenantMismatch",
                "TenantMismatch");
        assertThat(trail).filteredOn(r -> r.get("reason") == null).singleElement()
                .satisfies(r -> assertThat((String) r.get("details")).contains("\"returned_count\": 0"));
    }

    @Test
    void unknownHostTenant_isTenantNotResolved_auditedInPlatform() throws Exception {
        String marker = correlation();

        mvc.perform(query("http://atlantis.stg.eudistack.net", "/audit/api/v1/events?correlation_id=" + marker,
                        TestTokens.tenantAuditor("atlantis")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("tenant_not_resolved"));

        assertThat(rows("platform", "event_type = 'AuditTrailQueried' AND reason = 'TenantNotResolved' "
                + "AND details->'filters'->>'correlation_id' = ?", marker)).hasSize(1);
    }

    @Test
    void readWithoutToken_is401_andLeavesNothing() throws Exception {
        String marker = correlation();

        mvc.perform(query(RED_HOST, "/audit/api/v1/events?correlation_id=" + marker, null))
                .andExpect(status().isUnauthorized());

        assertThat(rows("red_logistica", "details->'filters'->>'correlation_id' = ?", marker)).isEmpty();
    }

    @Test
    void noStore_onEveryResponse() throws Exception {
        mvc.perform(query(RED_HOST, "/audit/api/v1/events", TestTokens.tenantAuditor("red-logistica")))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(query(RED_HOST, "/audit/api/v1/events?from=2000-01-01T00:00:00Z&to=2000-01-02T00:00:00Z",
                        TestTokens.tenantAuditor("red-logistica")))
                .andExpect(status().isConflict())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(write(TestEvents.batch(TestEvents.issuanceCompleted("sandbox", correlation(), "1")),
                        TestTokens.writer()))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(query(RED_HOST, "/audit/api/v1/events", null))
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}
