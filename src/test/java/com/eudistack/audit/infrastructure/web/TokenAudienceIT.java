package com.eudistack.audit.infrastructure.web;

import com.eudistack.audit.support.AuditIntegrationTest;
import com.eudistack.audit.support.TestEvents;
import com.eudistack.audit.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Design §3.4.1 with {@code audit.security.audience} set: right audience, missing audience and the
 * audience of another service, on write and read.
 */
@TestPropertySource(properties = "audit.security.audience=https://audit.internal.stg.eudistack.net")
class TokenAudienceIT extends AuditIntegrationTest {

    private static final String AUDIENCE = "https://audit.internal.stg.eudistack.net";

    private static String writer(String audience) {
        TestTokens.Builder builder = TestTokens.token().system("issuer-client").tenant("platform")
                .organization("VATES-EUDISTACK").power("organization", "EUDISTACK", "Audit", "Write");
        return (audience == null ? builder : builder.audience(audience)).sign();
    }

    private static String reader(String audience) {
        TestTokens.Builder builder = TestTokens.token().person("auditor").tenant("red-logistica")
                .organization("VATES-B1").power("organization", "RED-LOGISTICA", "Audit", "Read");
        return (audience == null ? builder : builder.audience(audience)).sign();
    }

    @Test
    void write_audienceIsChecked() throws Exception {
        var batch = TestEvents.batch(TestEvents.issuanceCompleted("sandbox", correlation(), "1"));

        mvc.perform(write(batch, writer(AUDIENCE))).andExpect(status().isOk());
        mvc.perform(write(batch, writer(null))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("invalid_token"));
        mvc.perform(write(batch, writer("https://issuer.stg.eudistack.net"))).andExpect(status().isUnauthorized());
    }

    @Test
    void read_audienceIsChecked() throws Exception {
        mvc.perform(query(RED_HOST, "/audit/api/v1/events", reader(AUDIENCE))).andExpect(status().isOk());
        mvc.perform(query(RED_HOST, "/audit/api/v1/events", reader(null))).andExpect(status().isUnauthorized());
        mvc.perform(query(RED_HOST, "/audit/api/v1/events", reader("other"))).andExpect(status().isUnauthorized());
    }
}
