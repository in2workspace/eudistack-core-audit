package com.eudistack.audit.infrastructure.web;

import com.eudistack.audit.support.AuditIntegrationTest;
import com.eudistack.audit.support.TestEvents;
import com.eudistack.audit.support.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Design AD-11: {@code audit.security.audience} as a list whose values may hold the tenant marker, resolved
 * against the tenant of the host. The admin portal's session token carries the client of its own tenant as
 * {@code aud}; the Verifier's URL stays for the platform producers.
 */
@DisplayName("Audience with the tenant marker")
@TestPropertySource(properties =
        "audit.security.audience=https://audit.internal.stg.eudistack.net,vc-auth-client-{tenant}")
class TenantAudienceIT extends AuditIntegrationTest {

    private static final String FIXED = "https://audit.internal.stg.eudistack.net";

    private static String reader(String audience) {
        return TestTokens.token().person("auditor").tenant("red-logistica").organization("VATES-B1")
                .power("organization", "RED-LOGISTICA", "Audit", "Read").audience(audience).sign();
    }

    private static String writer(String audience) {
        return TestTokens.token().system("issuer-client").tenant("platform").organization("VATES-EUDISTACK")
                .power("organization", "EUDISTACK", "Audit", "Write").audience(audience).sign();
    }

    @Nested
    @DisplayName("read face")
    class ReadFace {

        @Test
        @DisplayName("list_clientOfTheHostTenant_isAccepted")
        void list_clientOfTheHostTenant_isAccepted() throws Exception {
            // Arrange
            String token = reader("vc-auth-client-red-logistica");

            // Act + Assert
            mvc.perform(query(RED_HOST, "/audit/api/v1/events", token)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("list_clientOfTheHostTenantOnAnotherTenantHost_isInvalidToken")
        void list_clientOfTheHostTenantOnAnotherTenantHost_isInvalidToken() throws Exception {
            // Arrange
            String token = reader("vc-auth-client-red-logistica");

            // Act + Assert
            mvc.perform(query(SANDBOX_HOST, "/audit/api/v1/events", token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value("invalid_token"));
        }

        @Test
        @DisplayName("list_clientOfAnotherTenant_isInvalidToken")
        void list_clientOfAnotherTenant_isInvalidToken() throws Exception {
            // Arrange
            String token = reader("vc-auth-client-sandbox");

            // Act + Assert
            mvc.perform(query(RED_HOST, "/audit/api/v1/events", token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value("invalid_token"));
        }

        @Test
        @DisplayName("list_otherClient_isInvalidToken")
        void list_otherClient_isInvalidToken() throws Exception {
            // Arrange
            String token = reader("some-other-client");

            // Act + Assert
            mvc.perform(query(RED_HOST, "/audit/api/v1/events", token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value("invalid_token"));
        }

        @Test
        @DisplayName("list_fixedAudience_isStillAccepted")
        void list_fixedAudience_isStillAccepted() throws Exception {
            // Arrange
            String token = reader(FIXED);

            // Act + Assert
            mvc.perform(query(RED_HOST, "/audit/api/v1/events", token)).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("write face")
    class WriteFace {

        @Test
        @DisplayName("write_fixedAudience_isAccepted")
        void write_fixedAudience_isAccepted() throws Exception {
            // Arrange
            var batch = TestEvents.batch(TestEvents.issuanceCompleted("sandbox", correlation(), "1"));

            // Act + Assert
            mvc.perform(write(batch, writer(FIXED))).andExpect(status().isOk());
        }

        @Test
        @DisplayName("write_audienceWithTheTenantMarker_isInvalidToken")
        void write_audienceWithTheTenantMarker_isInvalidToken() throws Exception {
            // Arrange
            var batch = TestEvents.batch(TestEvents.issuanceCompleted("sandbox", correlation(), "1"));

            // Act + Assert
            mvc.perform(write(batch, writer("vc-auth-client-platform")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value("invalid_token"));
        }
    }
}
