package com.eudistack.audit.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import com.eudistack.audit.domain.exception.BadRequestException;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;
import com.eudistack.audit.infrastructure.config.AuditRole;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostPolicyFilterTest {

    private static final int MAX = 8;

    private final HostPolicyFilter filter = new HostPolicyFilter(List.of("audit\\.internal\\..+"), AuditRole.WRITER,
            new ProblemMapper(new ObjectMapper()), AuditTelemetryPort.noop(), MAX);

    @Test
    void postWithoutContentLengthIsRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(write(), response, (request, servletResponse) -> {
            throw new AssertionError("the chain must not run");
        });

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("Content-Length");
    }

    @Test
    void postLargerThanTheLimitIsRejected() throws Exception {
        MockHttpServletRequest request = write();
        request.setContent(new byte[MAX + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            throw new AssertionError("the chain must not run");
        });

        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void postThatExceedsTheLimitAfterTheHeaderIsRejectedWhenRead() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return MAX;
            }
        };
        request.setRequestURI("/api/v1/events");
        request.setMethod("POST");
        request.setServerName("audit.internal.stg.eudistack.net");
        request.setContent(new byte[MAX + 1]);
        FilterChain chain = (ServletRequest incoming, ServletResponse response) ->
                incoming.getInputStream().readAllBytes();

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), chain))
                .isInstanceOf(BadRequestException.class);
    }

    private static MockHttpServletRequest write() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/events");
        request.setMethod("POST");
        request.setServerName("audit.internal.stg.eudistack.net");
        return request;
    }
}
