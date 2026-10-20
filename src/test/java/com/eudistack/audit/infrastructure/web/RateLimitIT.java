package com.eudistack.audit.infrastructure.web;

import com.eudistack.audit.support.AuditIntegrationTest;
import com.eudistack.audit.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-16 with the limits of the PRD: 60 queries per minute and organization for systems, 30 per person,
 * of which path searches 20 and 10. Limits can be raised for one organization.
 */
@TestPropertySource(properties = {
        "audit.query.rate-limit.system=60",
        "audit.query.rate-limit.person=30",
        "audit.query.rate-limit.system-search=20",
        "audit.query.rate-limit.person-search=10",
        "audit.query.rate-limit-overrides.VATES-GRANDE.system=1000",
        "audit.query.rate-limit-overrides.VATES-GRANDE.person=1000",
        "audit.query.rate-limit-overrides.VATES-GRANDE.system-search=1000",
        "audit.query.rate-limit-overrides.VATES-GRANDE.person-search=1000"})
class RateLimitIT extends AuditIntegrationTest {

    private String systemReader(String organization) {
        return TestTokens.token().system("client-" + UUID.randomUUID()).tenant("red-logistica")
                .organization(organization).power("organization", "RED-LOGISTICA", "Audit", "Read").sign();
    }

    /**
     * The limiter counts in fixed one-minute windows: start each test well inside a window.
     */
    @org.junit.jupiter.api.BeforeEach
    void freshWindow() throws InterruptedException {
        int second = java.time.LocalTime.now(java.time.ZoneOffset.UTC).getSecond();
        if (second > 35) {
            Thread.sleep((61L - second) * 1000);
        }
    }

    private int sendUntilLimited(String token, String query, int max) throws Exception {
        for (int i = 1; i <= max; i++) {
            int status = mvc.perform(query(RED_HOST, "/audit/api/v1/events" + query, token))
                    .andReturn().getResponse().getStatus();
            if (status == 429) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void ac16_sixtyFirstSystemQueryOfAnOrganization_isRateLimited() throws Exception {
        String organization = "VATES-" + UUID.randomUUID();
        // Several systems of the same organization share its quota.
        for (int i = 0; i < 59; i++) {
            mvc.perform(query(RED_HOST, "/audit/api/v1/events", systemReader(organization))).andExpect(status().isOk());
        }
        int limitedAt = sendUntilLimited(systemReader(organization), "", 5);

        assertThat(limitedAt).isEqualTo(2);
        mvc.perform(query(RED_HOST, "/audit/api/v1/events", systemReader(organization)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("rate_limited"));
    }

    @Test
    void personLimit_is30() throws Exception {
        String person = TestTokens.token().person("p-" + UUID.randomUUID()).tenant("red-logistica")
                .organization("VATES-X").power("organization", "RED-LOGISTICA", "Audit", "Read").sign();

        assertThat(sendUntilLimited(person, "", 40)).isEqualTo(31);
    }

    @Test
    void pathSearchLimit_isLowerForPersons() throws Exception {
        String person = TestTokens.token().person("p-" + UUID.randomUUID()).tenant("red-logistica")
                .organization("VATES-X").power("organization", "RED-LOGISTICA", "Audit", "Read").sign();
        String search = "?event_type=IssuanceCompleted&details_path=holder.identifier&details_value=1&from="
                + java.time.Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

        assertThat(sendUntilLimited(person, search, 20)).isEqualTo(11);
    }

    @Test
    void raisedLimitForAnOrganization_isApplied() throws Exception {
        assertThat(sendUntilLimited(systemReader("VATES-GRANDE"), "", 70)).isEqualTo(-1);
    }

    @Test
    void ad08_rateLimitedQuery_leavesNoAuditTrail() throws Exception {
        String organization = "VATES-" + UUID.randomUUID();
        String token = systemReader(organization);
        sendUntilLimited(token, "?correlation_id=" + organization, 61);

        assertThat(rows("red_logistica", "event_type = 'AuditTrailQueried' AND details->'filters'->>'correlation_id' = ?",
                organization)).hasSize(60);
    }
}
