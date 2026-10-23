package com.eudistack.audit.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import com.eudistack.audit.domain.exception.BadRequestException;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;
import com.eudistack.audit.infrastructure.config.AuditRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
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
    void postThatExceedsTheLimitAfterTheHeaderIsRejectedWhenRead() {
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
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void postWithinTheLimitIsReadThroughTheBoundedStream() throws Exception {
        MockHttpServletRequest request = write();
        request.setContent("{\"ok\":1}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (incoming, servletResponse) -> {
            assertThat(incoming.getAttribute(ApiRequest.FACE)).isEqualTo(ApiRequest.Face.WRITE);
            assertThat(incoming.getReader().readLine()).isEqualTo("{\"ok\":1}");
            ServletInputStream again = incoming.getInputStream();
            assertThat(again.isReady()).isTrue();
            assertThat(again.isFinished()).isTrue();
            assertThat(again.read()).isEqualTo(-1);
            assertThatThrownBy(() -> again.setReadListener(null)).isInstanceOf(UnsupportedOperationException.class);
        });

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void writeRoleOnATenantHostIsNotARead() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/events");
        request.setMethod("GET");
        request.setServerName("red-logistica.stg.eudistack.net");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (incoming, servletResponse) -> {
            throw new AssertionError("the chain must not run");
        });

        assertThat(response.getStatus()).isEqualTo(405);
    }

    @Test
    void getOnATenantHostIsTheReadFace() throws Exception {
        HostPolicyFilter reader = new HostPolicyFilter(List.of("audit\\.internal\\..+"), AuditRole.READER,
                new ProblemMapper(new ObjectMapper()), AuditTelemetryPort.noop(), MAX);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/events");
        request.setMethod("GET");
        request.setServerName("red-logistica.stg.eudistack.net");
        MockHttpServletResponse response = new MockHttpServletResponse();

        reader.doFilter(request, response, (incoming, servletResponse) ->
                assertThat(incoming.getAttribute(ApiRequest.FACE)).isEqualTo(ApiRequest.Face.READ));

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void getOnTheWriteHostIsNotAllowed() throws Exception {
        MockHttpServletRequest request = write();
        request.setMethod("GET");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (incoming, servletResponse) -> {
            throw new AssertionError("the chain must not run");
        });

        assertThat(response.getStatus()).isEqualTo(405);
        assertThat(response.getHeader(HttpHeaders.ALLOW)).isEqualTo("POST");
    }

    @Test
    void browserOriginOnTheWriteFaceIsForbidden() throws Exception {
        MockHttpServletRequest request = write();
        request.setContent(new byte[] {1});
        request.addHeader(HttpHeaders.ORIGIN, "https://example.test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (incoming, servletResponse) -> {
            throw new AssertionError("the chain must not run");
        });

        assertThat(response.getStatus()).isEqualTo(403);
    }

    private static MockHttpServletRequest write() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/events");
        request.setMethod("POST");
        request.setServerName("audit.internal.stg.eudistack.net");
        return request;
    }
}
