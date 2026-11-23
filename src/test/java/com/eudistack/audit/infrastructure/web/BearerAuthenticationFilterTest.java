package com.eudistack.audit.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.eudistack.audit.domain.model.ActorKind;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.port.in.RecordEventsPort;
import com.eudistack.audit.domain.port.out.TokenVerifier;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BearerAuthenticationFilter")
class BearerAuthenticationFilterTest {

    private static final Caller CALLER = new Caller("user-1", ActorKind.PERSON, "Ana", null, "VATES-B1",
            "red-logistica", null, List.of());

    private final List<String> verifiedFor = new ArrayList<>();

    private BearerAuthenticationFilter filter() {
        TokenVerifier verifier = (token, hostTenant) -> {
            verifiedFor.add(hostTenant);
            return CALLER;
        };
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        return new BearerAuthenticationFilter(verifier, beans.getBeanProvider(RecordEventsPort.class),
                new BearerAuthenticationFilter.RejectionSupport(new ProblemMapper(new ObjectMapper()),
                        new SecurityLog(Clock.systemUTC())),
                new TraceIds(beans.getBeanProvider(Tracer.class)), new ObjectMapper(),
                new HostTenantResolver("X-Tenant-Id", List.of("-stg"), List.of()), 1024);
    }

    private static MockHttpServletRequest request(String host, ApiRequest.Face face) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName(host);
        request.addHeader("Authorization", "Bearer a-token");
        request.setAttribute(ApiRequest.FACE, face);
        return request;
    }

    @Test
    @DisplayName("doFilter_readFace_verifiesAgainstTheHostTenant")
    void doFilter_readFace_verifiesAgainstTheHostTenant() throws Exception {
        // Arrange
        MockHttpServletRequest request = request("red-logistica.stg.eudistack.net", ApiRequest.Face.READ);

        // Act
        filter().doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        // Assert
        assertThat(verifiedFor).containsExactly("red-logistica");
        assertThat(request.getAttribute(ApiRequest.CALLER)).isEqualTo(CALLER);
    }

    @Test
    @DisplayName("doFilter_writeFace_verifiesWithoutTenant")
    void doFilter_writeFace_verifiesWithoutTenant() throws Exception {
        // Arrange
        MockHttpServletRequest request = request("audit.internal.stg.eudistack.net", ApiRequest.Face.WRITE);

        // Act
        filter().doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        // Assert
        assertThat(verifiedFor).singleElement().isNull();
    }
}
