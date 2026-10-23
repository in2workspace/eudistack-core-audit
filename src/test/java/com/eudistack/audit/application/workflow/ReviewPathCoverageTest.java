package com.eudistack.audit.application.workflow;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eudistack.audit.domain.catalogue.CatalogueVersion;
import com.eudistack.audit.domain.catalogue.EventTypeSpec;
import com.eudistack.audit.domain.exception.BadRequestException;
import com.eudistack.audit.domain.exception.InsufficientPermissionException;
import com.eudistack.audit.domain.exception.RateLimitedException;
import com.eudistack.audit.domain.exception.TenantNotResolvedException;
import com.eudistack.audit.domain.model.ActorKind;
import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.model.Power;
import com.eudistack.audit.domain.model.QuerySpec;
import com.eudistack.audit.domain.model.ReadScope;
import com.eudistack.audit.domain.model.StoredEvent;
import com.eudistack.audit.domain.model.TenantRef;
import com.eudistack.audit.domain.policy.CursorCodec;
import com.eudistack.audit.domain.policy.NulCharacters;
import com.eudistack.audit.domain.policy.SelfEventFactory;
import com.eudistack.audit.domain.port.in.ListRequest;
import com.eudistack.audit.domain.port.in.QueryContext;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;
import com.eudistack.audit.domain.port.out.CatalogueResolverPort;
import com.eudistack.audit.domain.port.out.EventQueryPort;
import com.eudistack.audit.domain.port.out.RateLimiterPort;
import com.eudistack.audit.domain.port.out.SelfAuditPort;
import com.eudistack.audit.domain.port.out.TenantDirectoryPort;
import com.eudistack.audit.support.InMemoryColdStorage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewPathCoverageTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Caller READER = new Caller("auditor", ActorKind.PERSON, null, null, "VATES-B1",
            "red-logistica", null, List.of(new Power("organization", "RED-LOGISTICA", "Audit", List.of("Read"))));
    private static final Caller ADMIN = new Caller("ops", ActorKind.SYSTEM, null, null, "EUDISTACK", "platform", null,
            List.of(new Power("organization", "EUDISTACK", "System", List.of("Administration"))));

    private final List<AuditEvent> selfEvents = new ArrayList<>();
    private final SelfAuditPort selfAudit = (target, event) -> selfEvents.add(event);
    private final SelfEventFactory factory = new SelfEventFactory(CLOCK, "2.2.0");
    private final QueryAccessGuard guard = new QueryAccessGuard(directory(), selfAudit, factory);

    @Test
    void listing_rejectsABadLimitABadDateAndAValueWithoutAPath_andServesAnEmptyPage() {
        ListEventsWorkflow workflow = new ListEventsWorkflow(guard, emptyQuery(), catalogue(),
                new CursorCodec("0123456789abcdef0123456789abcdef".getBytes()), AuditTelemetryPort.noop(), CLOCK);
        QueryContext context = new QueryContext(READER, "red-logistica", "trace-1");
        ListRequest badLimit = request(null, null, null, "abc");
        ListRequest badDate = request("yesterday", null, null, null);
        ListRequest valueWithoutPath = request(null, null, "needle", null);
        ListRequest pathWithoutValue = requestWithPath();

        assertThatThrownBy(() -> workflow.list(context, badLimit)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> workflow.list(context, badDate)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> workflow.list(context, valueWithoutPath)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> workflow.list(context, pathWithoutValue)).isInstanceOf(BadRequestException.class);
        assertThat(workflow.list(context, request(null, null, null, null)).events()).isEmpty();
        assertThat(selfEvents).isNotEmpty();
    }

    @Test
    void accessGuard_recordsAGrantAndADenial() {
        ObjectNode filters = JsonNodeFactory.instance.objectNode();
        QueryContext context = new QueryContext(READER, "red-logistica", "trace-1");
        ReadScope scope = guard.authorize(context, null, SelfEventFactory.OPERATION_LIST, filters);
        guard.recordGranted(context, scope, SelfEventFactory.OPERATION_LIST, filters, 0);

        QueryContext unresolved = new QueryContext(READER, null, "trace-1");
        assertThatThrownBy(() -> guard.authorize(unresolved, null, SelfEventFactory.OPERATION_LIST, filters))
                .isInstanceOf(TenantNotResolvedException.class);
        assertThat(selfEvents).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void recovery_recordsTheDenialAndAnEmptyDelivery() {
        InMemoryColdStorage storage = new InMemoryColdStorage(CLOCK);
        RecoverArchiveWorkflow recovery = new RecoverArchiveWorkflow(missingDirectory(), storage,
                new ArchiveEventsWorkflow(null, null, storage, factory, AuditTelemetryPort.noop(), CLOCK),
                selfAudit, factory);
        LocalDate day = LocalDate.parse("2026-01-01");

        assertThatThrownBy(() -> recovery.recover(READER, "red-logistica", day, day, "trace-1"))
                .isInstanceOf(InsufficientPermissionException.class);
        assertThat(recovery.recover(ADMIN, "platform", day, day, "trace-1")).isEmpty();
        assertThat(selfEvents).hasSize(2);
    }

    @Test
    void rateLimit_usesTheOrganizationForASystemAndTheSubjectForAPerson() {
        RateLimiterPort counters = (key, window) -> 1;
        QueryRateLimiter limiter = new QueryRateLimiter(counters, CLOCK,
                new QueryRateLimiter.Limits(60, 30, 20, 10), Map.of(), AuditTelemetryPort.noop());
        Caller system = new Caller("client", ActorKind.SYSTEM, null, null, "VATES-B1", "red-logistica", null,
                List.of());

        limiter.check(system, true);
        limiter.check(new Caller("person", ActorKind.PERSON, null, null, null, "red-logistica", null, List.of()),
                false);

        QueryRateLimiter tight = new QueryRateLimiter((key, window) -> 100, CLOCK,
                new QueryRateLimiter.Limits(1, 1, 1, 1), Map.of(), AuditTelemetryPort.noop());
        assertThatThrownBy(() -> tight.check(READER, false)).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void cursor_rejectsBytesThatAreNotACursor() {
        CursorCodec codec = new CursorCodec("0123456789abcdef0123456789abcdef".getBytes());

        assertThatThrownBy(() -> codec.decode("ab.@@@")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void nul_isFoundInAnArrayValueAndInAFieldName() {
        ObjectNode array = JsonNodeFactory.instance.objectNode();
        array.putArray("items").add("a\u0000b");
        ObjectNode named = JsonNodeFactory.instance.objectNode();
        named.put("a\u0000", "x");

        assertThat(NulCharacters.firstPath(array)).contains("items[0]");
        assertThat(NulCharacters.firstPath(named)).isPresent();
    }

    private static ListRequest request(String from, String to, String detailsValue, String limit) {
        return new ListRequest(from, to, List.of(), List.of(), null, null, null, null, detailsValue, null, null,
                limit, null, JsonNodeFactory.instance.objectNode());
    }

    private static ListRequest requestWithPath() {
        return new ListRequest(null, null, List.of(), List.of(), null, null, null, "holder", null, null, null,
                null, null, JsonNodeFactory.instance.objectNode());
    }

    private static TenantDirectoryPort directory() {
        return new TenantDirectoryPort() {
            @Override
            public Optional<TenantRef> find(String tenantId) {
                return Optional.of(TenantRef.forNewTenant(tenantId));
            }

            @Override
            public List<TenantRef> tenants() {
                return List.of();
            }
        };
    }

    private static TenantDirectoryPort missingDirectory() {
        return new TenantDirectoryPort() {
            @Override
            public Optional<TenantRef> find(String tenantId) {
                return Optional.empty();
            }

            @Override
            public List<TenantRef> tenants() {
                return List.of();
            }
        };
    }

    private static EventQueryPort emptyQuery() {
        return new EventQueryPort() {
            @Override
            public List<StoredEvent> find(ReadScope scope, QuerySpec spec) {
                return List.of();
            }

            @Override
            public Optional<StoredEvent> findById(ReadScope scope, UUID eventId, Instant onlineFrom) {
                return Optional.empty();
            }
        };
    }

    private static CatalogueResolverPort catalogue() {
        return new CatalogueResolverPort() {
            @Override
            public Optional<CatalogueVersion> version(String version) {
                return Optional.empty();
            }

            @Override
            public Collection<CatalogueVersion> all() {
                return List.of();
            }

            @Override
            public CatalogueVersion latest() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<EventTypeSpec> latestSpecOf(String eventType) {
                return Optional.empty();
            }
        };
    }
}
