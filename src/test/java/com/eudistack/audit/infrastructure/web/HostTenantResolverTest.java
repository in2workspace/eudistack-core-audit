package com.eudistack.audit.infrastructure.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HostTenantResolver")
class HostTenantResolverTest {

    private static final String TRUSTED_PROXY = "10.0.0.8";

    private final HostTenantResolver resolver = new HostTenantResolver(
            "X-Tenant-Id", List.of("-stg", "-dev", "-pre"), List.of(TRUSTED_PROXY));

    private static MockHttpServletRequest request(String host, String remote) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName(host);
        request.addHeader("X-Tenant-Id", "sandbox");
        request.setRemoteAddr(remote);
        return request;
    }

    @Nested
    @DisplayName("from the host")
    class FromTheHost {

        @Test
        @DisplayName("resolve_tenantHost_isTheFirstLabel")
        void resolve_tenantHost_isTheFirstLabel() {
            // Arrange
            MockHttpServletRequest request = request("red-logistica.stg.eudistack.net", "203.0.113.4");

            // Act
            String tenant = resolver.resolve(request);

            // Assert
            assertThat(tenant).isEqualTo("red-logistica");
        }

        @Test
        @DisplayName("resolve_environmentSuffixInTheLabel_isStripped")
        void resolve_environmentSuffixInTheLabel_isStripped() {
            // Arrange
            MockHttpServletRequest request = request("Sandbox-STG.eudistack.net", "203.0.113.4");

            // Act
            String tenant = resolver.resolve(request);

            // Assert
            assertThat(tenant).isEqualTo("sandbox");
        }

        @Test
        @DisplayName("resolve_ipAddress_isNull")
        void resolve_ipAddress_isNull() {
            // Arrange
            MockHttpServletRequest request = request("10.1.2.3", "203.0.113.4");

            // Act
            String tenant = resolver.resolve(request);

            // Assert
            assertThat(tenant).isNull();
        }

        @Test
        @DisplayName("resolve_hostWithoutDot_isNull")
        void resolve_hostWithoutDot_isNull() {
            // Arrange
            MockHttpServletRequest request = request("localhost", "203.0.113.4");

            // Act
            String tenant = resolver.resolve(request);

            // Assert
            assertThat(tenant).isNull();
        }

        @Test
        @DisplayName("resolve_labelThatIsNotAValidTenant_isNull")
        void resolve_labelThatIsNotAValidTenant_isNull() {
            // Arrange
            MockHttpServletRequest request = request("bad_tenant.stg.eudistack.net", "203.0.113.4");

            // Act
            String tenant = resolver.resolve(request);

            // Assert
            assertThat(tenant).isNull();
        }
    }

    @Nested
    @DisplayName("from the tenant header")
    class FromTheTenantHeader {

        @Test
        @DisplayName("resolve_headerFromAnUntrustedClient_isIgnored")
        void resolve_headerFromAnUntrustedClient_isIgnored() {
            // Arrange
            MockHttpServletRequest request = request("red-logistica.stg.eudistack.net", "203.0.113.4");

            // Act
            String tenant = resolver.resolve(request);

            // Assert
            assertThat(tenant).isEqualTo("red-logistica");
        }

        @Test
        @DisplayName("resolve_headerFromATrustedProxy_replacesTheHost")
        void resolve_headerFromATrustedProxy_replacesTheHost() {
            // Arrange
            MockHttpServletRequest request = request("red-logistica.stg.eudistack.net", TRUSTED_PROXY);

            // Act
            String tenant = resolver.resolve(request);

            // Assert
            assertThat(tenant).isEqualTo("sandbox");
        }

        @Test
        @DisplayName("resolve_blankHeaderName_neverReadsTheHeader")
        void resolve_blankHeaderName_neverReadsTheHeader() {
            // Arrange
            HostTenantResolver ignoringHeader = new HostTenantResolver("  ", List.of("-stg"), List.of(TRUSTED_PROXY));
            MockHttpServletRequest request = request("red-logistica.stg.eudistack.net", TRUSTED_PROXY);

            // Act
            String tenant = ignoringHeader.resolve(request);

            // Assert
            assertThat(tenant).isEqualTo("red-logistica");
        }

        @Test
        @DisplayName("resolve_blankHeaderValue_fallsBackToTheHost")
        void resolve_blankHeaderValue_fallsBackToTheHost() {
            // Arrange
            MockHttpServletRequest request = request("red-logistica.stg.eudistack.net", TRUSTED_PROXY);
            request.removeHeader("X-Tenant-Id");
            request.addHeader("X-Tenant-Id", " ");

            // Act
            String tenant = resolver.resolve(request);

            // Assert
            assertThat(tenant).isEqualTo("red-logistica");
        }
    }
}
