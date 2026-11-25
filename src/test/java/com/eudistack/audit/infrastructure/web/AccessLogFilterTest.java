package com.eudistack.audit.infrastructure.web;

import ch.qos.logback.classic.Level;
import com.eudistack.audit.support.LogCapture;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AccessLogFilterTest {

    private static final Instant START = Instant.parse("2026-11-19T12:00:00Z");
    private static final String EVENT_ID = "11111111-2222-3333-4444-555555555555";
    private static final String SECRET = "eyJhbGciOiJIUzI1NiJ9.payload.signature";

    @Test
    void healthAndActuatorProduceNoLine() throws Exception {
        AccessLogFilter filter = filter(Clock.systemUTC(), () -> 1.0, true, Duration.ofMillis(500));
        try (LogCapture capture = LogCapture.attach(AccessLogFilter.LOGGER_NAME)) {
            invoke(filter, request("/audit/health"), 200);
            invoke(filter, request("/actuator/health"), 200);
            invoke(filter, request("/actuator/prometheus"), 200);
            assertThat(capture.events()).isEmpty();
        }
    }

    @Test
    void disabledProducesNoLine() throws Exception {
        AccessLogFilter filter = filter(Clock.systemUTC(), () -> 1.0, false, Duration.ofMillis(500));
        try (LogCapture capture = LogCapture.attach(AccessLogFilter.LOGGER_NAME)) {
            MockHttpServletRequest request = apiRequest();
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/events/{eventId}");
            invoke(filter, request, 500);
            assertThat(capture.events()).isEmpty();
        }
    }

    @Test
    void sampleZeroLogsErrorsAndSlowCallsOnly() throws Exception {
        SteppingClock clock = new SteppingClock(START, Duration.ofMillis(10));
        AccessLogFilter filter = filter(clock, () -> 0.99, true, Duration.ofMillis(500));
        try (LogCapture capture = LogCapture.attach(AccessLogFilter.LOGGER_NAME)) {
            invoke(filter, patterned("/api/v1/events"), 200);
            invoke(filter, patterned("/api/v1/events"), 304);
            assertThat(capture.events()).isEmpty();

            clock.step = Duration.ofMillis(500);
            invoke(filter, patterned("/api/v1/events"), 200);
            invoke(filter, patterned("/audit/api/v1/events"), 400);
            invoke(filter, patterned("/api/v1/events"), 500);

            assertThat(capture.events()).hasSize(3);
            assertThat(capture.events().get(0).getLevel()).isEqualTo(Level.INFO);
            assertThat(LogCapture.value(capture.events().get(0), "status")).isEqualTo("200");
            assertThat(LogCapture.value(capture.events().get(0), "durationMs")).isEqualTo("500");
            assertThat(capture.events().get(1).getLevel()).isEqualTo(Level.WARN);
            assertThat(LogCapture.value(capture.events().get(1), "status")).isEqualTo("400");
            assertThat(LogCapture.value(capture.events().get(1), "route")).isEqualTo("/audit/api/v1/events");
            assertThat(capture.events().get(2).getLevel()).isEqualTo(Level.INFO);
            assertThat(LogCapture.value(capture.events().get(2), "status")).isEqualTo("500");
        }
    }

    @Test
    void aRejectionAlreadyLoggedIsNotRepeated() throws Exception {
        AccessLogFilter filter = filter(Clock.systemUTC(), () -> 1.0, true, Duration.ofMillis(500));
        try (LogCapture capture = LogCapture.attach(AccessLogFilter.LOGGER_NAME)) {
            MockHttpServletRequest request = patterned("/api/v1/events");
            request.setAttribute(ApiRequest.SECURITY_LOGGED, true);
            request.setAttribute(ApiRequest.PROBLEM_TYPE, "invalid_token");
            invoke(filter, request, 401);
            assertThat(capture.events()).isEmpty();
        }
    }

    @Test
    void lineKeepsThePatternAndDropsCredentialsPathQueryAndBody() throws Exception {
        SteppingClock clock = new SteppingClock(START, Duration.ofMillis(500));
        AccessLogFilter filter = filter(clock, () -> 0.0, true, Duration.ofMillis(500));
        try (LogCapture capture = LogCapture.attach(AccessLogFilter.LOGGER_NAME)) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/events/" + EVENT_ID);
            request.setQueryString("email=ada@example.com&cursor=" + SECRET);
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET);
            request.setContent(("{\"actor\":\"ada@example.com\",\"dni\":\"12345678Z\"}").getBytes(StandardCharsets.UTF_8));
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/events/{eventId}");
            request.setAttribute(ApiRequest.HOST_TENANT, "red-logistica");
            request.setAttribute(ApiRequest.PROBLEM_TYPE, "invalid_token");
            invoke(filter, request, 401);

            assertThat(capture.events()).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getMessage()).isEqualTo("API request");
                assertThat(LogCapture.value(event, "method")).isEqualTo("GET");
                assertThat(LogCapture.value(event, "route")).isEqualTo("/api/v1/events/{eventId}");
                assertThat(LogCapture.value(event, "status")).isEqualTo("401");
                assertThat(LogCapture.value(event, "tenant")).isEqualTo("red-logistica");
                assertThat(LogCapture.value(event, "error.type")).isEqualTo("invalid_token");
                String rendered = event.getMessage() + " " + event.getKeyValuePairs().stream()
                        .map(pair -> pair.key + "=" + pair.value)
                        .collect(Collectors.joining(" "));
                assertThat(rendered).doesNotContain(EVENT_ID, SECRET, "ada@example.com", "12345678Z", "Bearer",
                        "cursor");
            });
        }
    }

    @Test
    void anUnmatched404IsNotFoundAndOmitsAnUnresolvedTenant() throws Exception {
        AccessLogFilter filter = filter(Clock.systemUTC(), () -> 0.0, true, Duration.ofMillis(500));
        try (LogCapture capture = LogCapture.attach(AccessLogFilter.LOGGER_NAME)) {
            invoke(filter, request("/api/v1/missing"), 404);
            assertThat(capture.events()).singleElement().satisfies(event -> {
                assertThat(LogCapture.value(event, "route")).isEqualTo("NOT_FOUND");
                assertThat(event.getKeyValuePairs()).noneMatch(pair -> "tenant".equals(pair.key));
            });
        }
    }

    @Test
    void aShortCircuitWithoutAPatternIsUnknown() throws Exception {
        AccessLogFilter filter = filter(Clock.systemUTC(), () -> 0.0, true, Duration.ofMillis(500));
        try (LogCapture capture = LogCapture.attach(AccessLogFilter.LOGGER_NAME)) {
            invoke(filter, request("/api/v1/events"), 403);
            assertThat(LogCapture.value(capture.events().getFirst(), "route")).isEqualTo("UNKNOWN");
        }
    }

    private static AccessLogFilter filter(Clock clock, java.util.function.DoubleSupplier random, boolean enabled,
                                          Duration slow) {
        return new AccessLogFilter(enabled, slow, 0.0, clock, random);
    }

    private static void invoke(AccessLogFilter filter, MockHttpServletRequest request, int status) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> ((MockHttpServletResponse) res).setStatus(status);
        filter.doFilter(request, response, chain);
    }

    private static MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    private static MockHttpServletRequest patterned(String path) {
        MockHttpServletRequest request = request(path);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, path);
        return request;
    }

    private static MockHttpServletRequest apiRequest() {
        return request("/api/v1/events/" + EVENT_ID);
    }

    /** First read is the start; the next read is that instant plus {@link #step}. */
    private static final class SteppingClock extends Clock {
        private final Instant start;
        private Duration step;
        private boolean started;

        private SteppingClock(Instant start, Duration step) {
            this.start = start;
            this.step = step;
        }

        @Override
        public Instant instant() {
            if (!started) {
                started = true;
                return start;
            }
            started = false;
            return start.plus(step);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
