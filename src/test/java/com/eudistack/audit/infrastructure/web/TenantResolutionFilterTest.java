package com.eudistack.audit.infrastructure.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TenantResolutionFilter")
class TenantResolutionFilterTest {

    private final TenantResolutionFilter filter = new TenantResolutionFilter(
            new HostTenantResolver("X-Tenant-Id", List.of("-stg"), List.of()));

    private static MockHttpServletRequest request(ApiRequest.Face face) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("red-logistica.stg.eudistack.net");
        request.setAttribute(ApiRequest.FACE, face);
        return request;
    }

    @Test
    @DisplayName("doFilter_readFace_publishesTheHostTenant")
    void doFilter_readFace_publishesTheHostTenant() throws Exception {
        // Arrange
        MockHttpServletRequest request = request(ApiRequest.Face.READ);

        // Act
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        // Assert
        assertThat(request.getAttribute(ApiRequest.HOST_TENANT)).isEqualTo("red-logistica");
    }

    @Test
    @DisplayName("doFilter_writeFace_publishesNoTenant")
    void doFilter_writeFace_publishesNoTenant() throws Exception {
        // Arrange
        MockHttpServletRequest request = request(ApiRequest.Face.WRITE);

        // Act
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        // Assert
        assertThat(request.getAttribute(ApiRequest.HOST_TENANT)).isNull();
    }
}
