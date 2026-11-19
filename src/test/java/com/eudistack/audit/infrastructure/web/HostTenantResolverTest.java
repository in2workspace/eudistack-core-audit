package com.eudistack.audit.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("HostTenantResolver")
class HostTenantResolverTest {

    private static final String TRUSTED_PROXY = "10.0.0.8";
    private static final String UNTRUSTED_CLIENT = "203.0.113.4";

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

        @ParameterizedTest(name = "{0} is the tenant {1}")
        @CsvSource({
                "red-logistica.stg.eudistack.net, red-logistica",
                "Sandbox-STG.eudistack.net, sandbox",
                "sandbox.eudistack.net, sandbox"})
        @DisplayName("resolve_hostWithATenantLabel_isTheTenant")
        void resolve_hostWithATenantLabel_isTheTenant(String host, String expected) {
            // Arrange
            MockHttpServletRequest request = request(host, UNTRUSTED_CLIENT);

            // Act
            String tenant = resolver.resolve(request);

            // Assert
            assertThat(tenant).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} has no tenant")
        @ValueSource(strings = {"10.1.2.3", "[::1]", "localhost", "bad_tenant.stg.eudistack.net", "-stg.eudistack.net"})
        @DisplayName("resolve_hostWithoutATenant_isNull")
        void resolve_hostWithoutATenant_isNull(String host) {
            // Arrange
            MockHttpServletRequest request = request(host, UNTRUSTED_CLIENT);

            // Act
            String tenant = resolver.resolve(request);

            // Assert
            assertThat(tenant).isNull();
        }

        @Test
        @DisplayName("resolve_requestWithoutServerName_isNull")
        void resolve_requestWithoutServerName_isNull() {
            // Arrange
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getRemoteAddr()).thenReturn(UNTRUSTED_CLIENT);

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
            MockHttpServletRequest request = request("red-logistica.stg.eudistack.net", UNTRUSTED_CLIENT);

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

        @ParameterizedTest(name = "header name \"{0}\" never reads the header")
        @ValueSource(strings = {"  ", ""})
        @DisplayName("resolve_blankHeaderName_neverReadsTheHeader")
        void resolve_blankHeaderName_neverReadsTheHeader(String headerName) {
            // Arrange
            HostTenantResolver ignoringHeader = new HostTenantResolver(headerName, List.of("-stg"), List.of(TRUSTED_PROXY));
            MockHttpServletRequest request = request("red-logistica.stg.eudistack.net", TRUSTED_PROXY);

            // Act
            String tenant = ignoringHeader.resolve(request);

            // Assert
            assertThat(tenant).isEqualTo("red-logistica");
        }

        @Test
        @DisplayName("resolve_noHeaderName_neverReadsTheHeader")
        void resolve_noHeaderName_neverReadsTheHeader() {
            // Arrange
            HostTenantResolver ignoringHeader = new HostTenantResolver(null, List.of("-stg"), List.of(TRUSTED_PROXY));
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

        @Test
        @DisplayName("resolve_trustedProxyWithoutHeader_fallsBackToTheHost")
        void resolve_trustedProxyWithoutHeader_fallsBackToTheHost() {
            // Arrange
            MockHttpServletRequest request = request("red-logistica.stg.eudistack.net", TRUSTED_PROXY);
            request.removeHeader("X-Tenant-Id");

            // Act
            String tenant = resolver.resolve(request);

            // Assert
            assertThat(tenant).isEqualTo("red-logistica");
        }
    }
}
