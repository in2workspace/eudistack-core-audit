package com.eudistack.audit.infrastructure.web;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.eudistack.audit.application.workflow.QueryRateLimiter;
import com.eudistack.audit.domain.exception.BadRequestException;
import com.eudistack.audit.domain.exception.EventNotFoundException;
import com.eudistack.audit.domain.exception.InsufficientPermissionException;
import com.eudistack.audit.domain.exception.InvalidTokenException;
import com.eudistack.audit.domain.exception.PeriodArchivedException;
import com.eudistack.audit.domain.exception.ServiceUnavailableException;
import com.eudistack.audit.domain.model.ActorKind;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.model.EventOutcome;
import com.eudistack.audit.domain.model.RejectionReason;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;
import com.eudistack.audit.infrastructure.config.AuditRole;
import com.eudistack.audit.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityLogTest {

    private static final String CURSOR = "cursor-value-must-not-appear";
    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.payload.signature";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:30Z");

    private final ManualClock clock = new ManualClock(NOW);
    private final SecurityLog security = new SecurityLog(clock);

    @Test
    void httpRejectionsAreOneWarnWithTheProblemTypeAndNoPayload() {
        AuditExceptionHandler handler = new AuditExceptionHandler(new ProblemMapper(new ObjectMapper()), security);
        try (LogCapture capture = LogCapture.attach(SecurityLog.LOGGER_NAME)) {
            handler.domain(new InvalidTokenException("bad token " + TOKEN), patterned(401));
            handler.domain(new InsufficientPermissionException("ada@example.com"), patterned(403));
            handler.domain(new EventNotFoundException("event " + TOKEN), patterned(404));
            handler.methodNotAllowed(patterned(405));
            handler.domain(new PeriodArchivedException(NOW), patterned(409));
            handler.unreadable(patterned(400));

            assertThat(capture.events()).hasSize(6);
            assertThat(capture.events()).allSatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getMessage()).isEqualTo("Request rejected");
                assertThat(rendered(event)).doesNotContain(TOKEN, "ada@example.com");
            });
            assertThat(LogCapture.value(capture.events().get(0), "error.type")).isEqualTo("invalid_token");
            assertThat(LogCapture.value(capture.events().get(0), "status")).isEqualTo("401");
            assertThat(LogCapture.value(capture.events().get(0), "route")).isEqualTo("/api/v1/events/{eventId}");
            assertThat(LogCapture.value(capture.events().get(0), "tenant")).isEqualTo("red-logistica");
            assertThat(LogCapture.value(capture.events().get(1), "error.type")).isEqualTo("insufficient_permission");
            assertThat(LogCapture.value(capture.events().get(2), "error.type")).isEqualTo("event_not_found");
            assertThat(LogCapture.value(capture.events().get(3), "error.type")).isEqualTo("BAD_REQUEST");
            assertThat(LogCapture.value(capture.events().get(3), "status")).isEqualTo("405");
            assertThat(LogCapture.value(capture.events().get(4), "error.type")).isEqualTo("period_archived");
            assertThat(LogCapture.value(capture.events().get(5), "status")).isEqualTo("400");
        }
    }

    @Test
    void aServerErrorStaysAnErrorAndIsNotAlsoASecurityWarn() {
        AuditExceptionHandler handler = new AuditExceptionHandler(new ProblemMapper(new ObjectMapper()), security);
        try (LogCapture securityLog = LogCapture.attach(SecurityLog.LOGGER_NAME);
                LogCapture handlerLog = LogCapture.attach(AuditExceptionHandler.class.getName())) {
            handler.domain(new ServiceUnavailableException("down", null), patterned(503));
            handler.unexpected(new IllegalStateException("sql select from events where actor='ada@example.com'"),
                    patterned(500));
            assertThat(securityLog.events()).isEmpty();
            assertThat(handlerLog.events()).isNotEmpty()
                    .allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
        }
    }

    @Test
    void aRejectedBatchIsOneWarnOfCountsAndADuplicateBatchIsSilent() {
        MockHttpServletRequest request = patterned(200);
        try (LogCapture capture = LogCapture.attach(SecurityLog.LOGGER_NAME)) {
            security.rejectedBatch(request, List.of(
                    EventOutcome.duplicate("event-" + TOKEN),
                    EventOutcome.accepted("event-ok"),
                    EventOutcome.rejected("event-" + TOKEN, RejectionReason.UNKNOWN_EVENT_TYPE, List.of()),
                    EventOutcome.rejected("event-" + TOKEN, RejectionReason.INVALID_EVENT, List.of()),
                    EventOutcome.rejected("event-" + TOKEN, RejectionReason.UNKNOWN_TENANT, List.of()),
                    EventOutcome.rejected("event-" + TOKEN, RejectionReason.EVENT_ID_CONFLICT, List.of()),
                    EventOutcome.rejected("event-" + TOKEN, RejectionReason.INVALID_EVENT, List.of())));
            assertThat(capture.events()).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getMessage()).isEqualTo("Batch rejected events");
                assertThat(LogCapture.value(event, "UnknownEventType")).isEqualTo("1");
                assertThat(LogCapture.value(event, "InvalidEvent")).isEqualTo("2");
                assertThat(LogCapture.value(event, "UnknownTenant")).isEqualTo("1");
                assertThat(LogCapture.value(event, "EventIdConflict")).isEqualTo("1");
                assertThat(rendered(event)).doesNotContain(TOKEN, "event-");
            });
            assertThat(request.getAttribute(ApiRequest.SECURITY_LOGGED)).isEqualTo(Boolean.TRUE);
        }
        try (LogCapture capture = LogCapture.attach(SecurityLog.LOGGER_NAME)) {
            security.rejectedBatch(patterned(200), List.of(EventOutcome.duplicate("event-" + TOKEN)));
            assertThat(capture.events()).isEmpty();
        }
    }

    @Test
    void rateLimitAndInvalidCursorWarnOncePerMinuteAndReportTheSuppressedCount() throws Exception {
        Caller person = new Caller("subject-" + TOKEN, ActorKind.PERSON, null, "ada@example.com", "red-logistica",
                "red-logistica", null, List.of());
        AtomicInteger hits = new AtomicInteger(1);
        QueryRateLimiter limiter = new QueryRateLimiter((key, window) -> hits.incrementAndGet(), clock,
                new QueryRateLimiter.Limits(1, 1, 1, 1), java.util.Map.of(), AuditTelemetryPort.noop());
        RateLimitFilter filter = new RateLimitFilter(limiter, new ProblemMapper(new ObjectMapper()), security);
        AuditExceptionHandler handler = new AuditExceptionHandler(new ProblemMapper(new ObjectMapper()), security);

        try (LogCapture capture = LogCapture.attach(SecurityLog.LOGGER_NAME)) {
            for (int i = 0; i < 5; i++) {
                MockHttpServletRequest request = readRequest();
                request.setAttribute(ApiRequest.CALLER, person);
                request.setParameter("details_path", "holder.email");
                request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
                filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                    throw new AssertionError("chain");
                });
            }
            assertThat(capture.events()).singleElement().satisfies(event -> {
                assertThat(event.getMessage()).isEqualTo("Query rate limited");
                assertThat(LogCapture.value(event, "error.type")).isEqualTo("rate_limited");
                assertThat(LogCapture.value(event, "kind")).isEqualTo("person");
                assertThat(LogCapture.value(event, "search")).isEqualTo("false");
                assertThat(event.getKeyValuePairs()).noneMatch(pair -> "suppressed".equals(pair.key));
                assertThat(rendered(event)).doesNotContain(TOKEN, "ada@example.com", "holder.email", "Bearer");
            });

            clock.advance(Duration.ofMinutes(1));
            MockHttpServletRequest later = readRequest();
            later.setAttribute(ApiRequest.CALLER, person);
            filter.doFilter(later, new MockHttpServletResponse(), (req, res) -> {
                throw new AssertionError("chain");
            });
            assertThat(capture.events()).hasSize(2);
            assertThat(LogCapture.value(capture.events().get(1), "suppressed")).isEqualTo("4");

            for (int i = 0; i < 3; i++) {
                MockHttpServletRequest request = patterned(400);
                request.setQueryString("cursor=" + CURSOR);
                handler.domain(new BadRequestException("cursor", "cannot read " + CURSOR), request);
            }
            assertThat(capture.events()).hasSize(3);
            assertThat(capture.events().get(2).getMessage()).isEqualTo("Invalid cursor");
            assertThat(LogCapture.value(capture.events().get(2), "error.type")).isEqualTo("invalid_cursor");
            assertThat(rendered(capture.events().get(2))).doesNotContain(CURSOR);

            clock.advance(Duration.ofMinutes(1));
            handler.domain(new BadRequestException("cursor", CURSOR), patterned(400));
            assertThat(capture.events()).hasSize(4);
            assertThat(LogCapture.value(capture.events().get(3), "suppressed")).isEqualTo("2");
            assertThat(rendered(capture.events().get(3))).doesNotContain(CURSOR);
        }
    }

    @Test
    void aBearer401IsLoggedAndAVerifierOutageIsNot() throws Exception {
        ProblemMapper problems = new ProblemMapper(new ObjectMapper());
        HostTenantResolver tenants = new HostTenantResolver("X-Tenant-Id", List.of(), List.of());
        BearerAuthenticationFilter.RejectionSupport rejections =
                new BearerAuthenticationFilter.RejectionSupport(problems, security);
        BearerAuthenticationFilter filter = new BearerAuthenticationFilter((token, hostTenant) -> {
            throw new ServiceUnavailableException("jwks " + TOKEN, null);
        }, empty(), rejections, new TraceIds(empty()), new ObjectMapper(), tenants, 64);
        BearerAuthenticationFilter missing = new BearerAuthenticationFilter((token, hostTenant) -> {
            throw new AssertionError(token);
        }, empty(), rejections, new TraceIds(empty()), new ObjectMapper(), tenants, 64);
        try (LogCapture capture = LogCapture.attach(SecurityLog.LOGGER_NAME)) {
            MockHttpServletRequest request = writeRequest();
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                throw new AssertionError("chain");
            });
            MockHttpServletRequest noHeader = writeRequest();
            missing.doFilter(noHeader, new MockHttpServletResponse(), (req, res) -> {
                throw new AssertionError("chain");
            });
            assertThat(capture.events()).singleElement().satisfies(event -> {
                assertThat(event.getMessage()).isEqualTo("Request rejected");
                assertThat(LogCapture.value(event, "error.type")).isEqualTo("authentication_error");
                assertThat(rendered(event)).doesNotContain(TOKEN);
            });
            assertThat(noHeader.getAttribute(ApiRequest.SECURITY_LOGGED)).isEqualTo(Boolean.TRUE);
            assertThat(request.getAttribute(ApiRequest.SECURITY_LOGGED)).isNull();
        }
    }

    @Test
    void hostPolicyRejectionsCarryTheProblemType() throws Exception {
        HostPolicyFilter filter = new HostPolicyFilter(List.of("audit\\.internal\\..+"), AuditRole.WRITER,
                new ProblemMapper(new ObjectMapper()), AuditTelemetryPort.noop(), security, 8);
        try (LogCapture capture = LogCapture.attach(SecurityLog.LOGGER_NAME)) {
            MockHttpServletRequest request = writeRequest();
            request.addHeader(HttpHeaders.ORIGIN, "https://example.test");
            request.setContent(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                throw new AssertionError("chain");
            });
            assertThat(capture.events()).singleElement().satisfies(event -> {
                assertThat(LogCapture.value(event, "status")).isEqualTo("403");
                assertThat(LogCapture.value(event, "error.type")).isEqualTo("insufficient_permission");
                assertThat(LogCapture.value(event, "route")).isEqualTo("UNKNOWN");
            });
        }
    }

    private static MockHttpServletRequest patterned(int status) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/events/event-" + TOKEN);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/events/{eventId}");
        request.setAttribute(ApiRequest.HOST_TENANT, "red-logistica");
        request.setAttribute("expected-status", status);
        return request;
    }

    private static MockHttpServletRequest readRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/audit/api/v1/events");
        request.setAttribute(ApiRequest.FACE, ApiRequest.Face.READ);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/audit/api/v1/events");
        request.setAttribute(ApiRequest.HOST_TENANT, "red-logistica");
        return request;
    }

    private static MockHttpServletRequest writeRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/events");
        request.setServerName("audit.internal.stg.eudistack.net");
        request.setAttribute(ApiRequest.FACE, ApiRequest.Face.WRITE);
        request.setContent(new byte[] {1});
        return request;
    }

    private static String rendered(ch.qos.logback.classic.spi.ILoggingEvent event) {
        return event.getMessage() + " " + event.getKeyValuePairs();
    }

    private static <T> ObjectProvider<T> empty() {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return null;
            }
        };
    }

    private static final class ManualClock extends Clock {
        private Instant now;

        private ManualClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration step) {
            now = now.plus(step);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
