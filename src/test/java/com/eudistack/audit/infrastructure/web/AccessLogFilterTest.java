package com.eudistack.audit.infrastructure.web;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.eudistack.audit.infrastructure.config.AuditProperties;
import com.eudistack.audit.infrastructure.config.AuditReadyLoggerTest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

class AccessLogFilterTest {

    private static final String TOKEN = "Bearer secret-token";
    private static final String EVENT_ID = "11111111-1111-1111-1111-111111111111";

    @Test
    void doFilter_Health_EmitsNothing() throws Exception {
        ListAppender<ILoggingEvent> logs = AuditReadyLoggerTest.attach(AccessLogFilter.class);
        try {
            filter(1.0).doFilter(request("GET", "/audit/health"), new MockHttpServletResponse(), ok(200));

            assertThat(logs.list).isEmpty();
        } finally {
            AuditReadyLoggerTest.detach(AccessLogFilter.class, logs);
        }
    }

    @Test
    void doFilter_ApiRequest_LogsTheRouteStatusAndTenantWithoutSecrets() throws Exception {
        MockHttpServletRequest request = request("POST", "/audit/api/v1/events/" + EVENT_ID);
        request.addHeader("Authorization", TOKEN);
        request.setQueryString("cursor=cursor-secret-value");
        request.setAttribute(ApiRequest.HOST_TENANT, "sandbox");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/audit/api/v1/events/{event_id}");
        MDC.put("traceId", "abc123");
        ListAppender<ILoggingEvent> logs = AuditReadyLoggerTest.attach(AccessLogFilter.class);
        try {
            filter(1.0).doFilter(request, new MockHttpServletResponse(), ok(201));

            assertThat(logs.list).hasSize(1);
            ILoggingEvent event = logs.list.getFirst();
            assertThat(event.getFormattedMessage()).isEqualTo("http request");
            assertThat(event.getKeyValuePairs()).extracting(pair -> pair.key + "=" + pair.value)
                    .contains("http.method=POST", "http.route=/audit/api/v1/events/{event_id}",
                            "http.status_code=201", "tenant=sandbox", "trace_id=abc123");
            assertThat(rendered(event)).doesNotContain("secret-token", "cursor-secret-value", EVENT_ID);
        } finally {
            MDC.clear();
            AuditReadyLoggerTest.detach(AccessLogFilter.class, logs);
        }
    }

    @Test
    void doFilter_PathWithoutPattern_CollapsesTheEventId() {
        MockHttpServletRequest request = request("GET", "/api/v1/events/" + EVENT_ID);

        assertThat(AccessLogFilter.route(request)).isEqualTo("/api/v1/events/{id}");
    }

    @Test
    void doFilter_SampleZero_SkipsSuccessAndKeepsErrors() throws Exception {
        ListAppender<ILoggingEvent> logs = AuditReadyLoggerTest.attach(AccessLogFilter.class);
        try {
            AccessLogFilter filter = filter(0.0);
            filter.doFilter(request("GET", "/api/v1/events"), new MockHttpServletResponse(), ok(200));
            filter.doFilter(request("GET", "/api/v1/events"), new MockHttpServletResponse(), ok(429));

            assertThat(logs.list).hasSize(1);
            assertThat(logs.list.getFirst().getKeyValuePairs()).extracting(pair -> pair.key + "=" + pair.value)
                    .contains("http.status_code=429");
        } finally {
            AuditReadyLoggerTest.detach(AccessLogFilter.class, logs);
        }
    }

    private static AccessLogFilter filter(double sample) {
        AuditProperties.AccessLog settings = new AuditProperties.AccessLog();
        settings.setEnabled(true);
        settings.setSample(sample);
        return new AccessLogFilter(settings);
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private static FilterChain ok(int status) {
        return (request, response) -> ((HttpServletResponse) response).setStatus(status);
    }

    private static String rendered(ILoggingEvent event) {
        return event.getFormattedMessage() + event.getKeyValuePairs();
    }
}
