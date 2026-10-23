package com.eudistack.audit.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.eudistack.audit.application.workflow.QueryRateLimiter;
import com.eudistack.audit.domain.exception.EventNotFoundException;
import com.eudistack.audit.domain.exception.ServiceUnavailableException;
import com.eudistack.audit.domain.model.ActorKind;
import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.model.EventOutcome;
import com.eudistack.audit.domain.port.in.GetEventPort;
import com.eudistack.audit.domain.port.in.RecordEventsPort;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;
import com.eudistack.audit.domain.port.out.TokenVerifier;
import com.eudistack.audit.infrastructure.config.AuditProperties;
import com.eudistack.audit.infrastructure.config.AuditRole;
import com.eudistack.audit.infrastructure.config.CoreConfiguration;
import com.eudistack.audit.infrastructure.config.ReaderConfiguration;
import com.eudistack.audit.infrastructure.config.WebConfiguration;
import com.eudistack.audit.infrastructure.persistence.repository.TenantDirectoryJdbc;
import com.eudistack.audit.infrastructure.security.CallerClaims;
import com.eudistack.audit.infrastructure.storage.FileSystemColdStorage;
import com.eudistack.audit.infrastructure.web.controller.EventsQueryController;
import com.eudistack.audit.infrastructure.web.controller.EventsWriteController;
import com.eudistack.audit.support.TestEvents;
import com.eudistack.audit.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewWebCoverageTest {

    private static final Caller CALLER = new Caller("auditor", ActorKind.PERSON, null, null, null, "red-logistica",
            null, List.of());

    @Test
    void problems_mapAMissingEventAndAnUnavailableStore() {
        ProblemMapper mapper = new ProblemMapper(new ObjectMapper());

        assertThat(mapper.map(new EventNotFoundException("gone"), "/events/1").problem().status()).isEqualTo(404);
        assertThat(mapper.map(new ServiceUnavailableException("down", null), "/events").problem().status())
                .isEqualTo(503);
    }

    @Test
    void writeController_turnsABrokenBodyIntoAnEmptyBatch() {
        AtomicReference<JsonNode> seen = new AtomicReference<>();
        RecordEventsPort port = new RecordEventsPort() {
            @Override
            public List<EventOutcome> recordBatch(Caller caller, JsonNode body, String traceId) {
                seen.set(body);
                return List.of(EventOutcome.accepted("1"));
            }

            @Override
            public void recordAuthenticationFailure(int eventCount, String traceId) {
                // not this path
            }
        };
        EventsWriteController controller = new EventsWriteController(port, new TraceIds(empty()), new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ApiRequest.CALLER, CALLER);

        controller.recordBatch(request, "{".getBytes(StandardCharsets.UTF_8));

        assertThat(seen.get()).isNull();
    }

    @Test
    void queryController_readsTheCallerFromTheRequest() {
        GetEventPort events = (context, eventId, tenant) -> {
            assertThat(context.hostTenantId()).isEqualTo("red-logistica");
            return AuditEvent.fromValidatedJson(TestEvents.issuanceCompleted("red-logistica", "c", "1Z"))
                    .withRecordedAt(Instant.parse("2026-10-19T12:00:00Z"));
        };
        EventsQueryController controller = new EventsQueryController(null, events, new TraceIds(empty()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ApiRequest.CALLER, CALLER);
        request.setAttribute(ApiRequest.HOST_TENANT, "red-logistica");

        assertThat(controller.get(request, "event-1").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void bearer_countsAnUnreadableBodyAndTurnsAMissingVerifierInto503() throws Exception {
        ProblemMapper problems = new ProblemMapper(new ObjectMapper());
        TokenVerifier down = token -> {
            throw new ServiceUnavailableException("jwks", null);
        };
        BearerAuthenticationFilter filter = new BearerAuthenticationFilter(down, empty(), problems,
                new TraceIds(empty()), new ObjectMapper(), 64);
        MockHttpServletRequest missing = write();
        MockHttpServletResponse missingResponse = new MockHttpServletResponse();
        filter.doFilter(missing, missingResponse, (request, response) -> {
            throw new AssertionError("chain");
        });
        MockHttpServletRequest broken = write();
        broken.setContent("{".getBytes(StandardCharsets.UTF_8));
        filter.doFilter(broken, new MockHttpServletResponse(), (request, response) -> {
            throw new AssertionError("chain");
        });
        MockHttpServletRequest invalid = write();
        invalid.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse invalidResponse = new MockHttpServletResponse();

        filter.doFilter(invalid, invalidResponse, (request, response) -> {
            throw new AssertionError("chain");
        });

        assertThat(missingResponse.getStatus()).isEqualTo(401);
        assertThat(invalidResponse.getStatus()).isEqualTo(503);
    }

    @Test
    void rateLimit_rejectsBeforeTheChain() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
        QueryRateLimiter limiter = new QueryRateLimiter((key, window) -> 100, clock,
                new QueryRateLimiter.Limits(1, 1, 1, 1), Map.of(), AuditTelemetryPort.noop());
        RateLimitFilter filter = new RateLimitFilter(limiter, new ProblemMapper(new ObjectMapper()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/events");
        request.setMethod("GET");
        request.setAttribute(ApiRequest.FACE, ApiRequest.Face.READ);
        request.setAttribute(ApiRequest.CALLER, CALLER);
        request.setParameter("details_path", "holder");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            throw new AssertionError("chain");
        });

        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void configuration_buildsTheFiltersAndRefusesAReaderWithoutACursorSecret() {
        AuditProperties properties = new AuditProperties();
        assertThat(properties.getWeb().getTrustedProxies()).isEmpty();
        WebConfiguration web = new WebConfiguration();
        assertThat(web.hostPolicyFilter(properties, new ProblemMapper(new ObjectMapper()), AuditTelemetryPort.noop())
                .getFilter()).isNotNull();
        assertThat(web.tenantResolutionFilter(properties).getFilter()).isNotNull();

        properties.setRole(AuditRole.ALL);
        assertThat(new ReaderConfiguration().cursorCodec(properties)).isNotNull();
        properties.setRole(AuditRole.READER);
        ReaderConfiguration readers = new ReaderConfiguration();
        assertThatThrownBy(() -> readers.cursorCodec(properties)).isInstanceOf(IllegalStateException.class);

        properties.setRole(AuditRole.WRITER);
        properties.getSecurity().setAudience("https://audit.example");
        properties.getSecurity().setJwks(TestTokens.publicJwks());
        properties.getSecurity().setTrustedIssuers(List.of("https://{tenant}.example.test/verifier"));
        assertThat(new CoreConfiguration().tokenVerifier(properties, new CallerClaims(List.of("-stg")))).isNotNull();
    }

    @Test
    void directory_resolvesPlatformAndIgnoresABlankIdentifier() {
        TenantDirectoryJdbc directory = new TenantDirectoryJdbc(null);

        assertThat(directory.find("")).isEmpty();
        assertThat(directory.find("platform")).contains(com.eudistack.audit.domain.model.TenantRef.PLATFORM);
    }

    @Test
    void filesystemStorage_writesAnObjectOnce(@TempDir Path root) {
        FileSystemColdStorage storage = new FileSystemColdStorage(root);

        storage.put("day/part", new byte[] {1, 2}, "application/gzip", Instant.EPOCH);

        assertThat(storage.get("day/part")).isPresent();
    }

    private static MockHttpServletRequest write() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/events");
        request.setMethod("POST");
        request.setAttribute(ApiRequest.FACE, ApiRequest.Face.WRITE);
        request.setContent("{\"events\":[{}]}".getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static <T> ObjectProvider<T> empty() {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return null;
            }
        };
    }
}
