package com.eudistack.audit.infrastructure.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TenantResolutionFilterTest {

    private final TenantResolutionFilter filter = new TenantResolutionFilter(
            "X-Tenant-Id", List.of("-stg", "-dev", "-pre"), List.of("10.0.0.8"));

    @Test
    void headerFromAnUntrustedClientIsIgnored() {
        MockHttpServletRequest request = request("203.0.113.4");

        assertThat(filter.resolve(request)).isEqualTo("red-logistica");
    }

    @Test
    void blankHeaderNameNeverReadsTheHeader() {
        TenantResolutionFilter ignored = new TenantResolutionFilter("  ", List.of("-stg"), List.of("10.0.0.8"));

        assertThat(ignored.resolve(request("10.0.0.8"))).isEqualTo("red-logistica");
    }

    @Test
    void headerFromATrustedProxyReplacesTheHost() {
        MockHttpServletRequest request = request("10.0.0.8");

        assertThat(filter.resolve(request)).isEqualTo("sandbox");
    }

    private static MockHttpServletRequest request(String remote) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("red-logistica.stg.eudistack.net");
        request.addHeader("X-Tenant-Id", "sandbox");
        request.setRemoteAddr(remote);
        return request;
    }
}
