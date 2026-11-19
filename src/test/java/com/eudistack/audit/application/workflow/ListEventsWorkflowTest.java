package com.eudistack.audit.application.workflow;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eudistack.audit.domain.catalogue.CatalogueVersion;
import com.eudistack.audit.domain.catalogue.EventTypeSpec;
import com.eudistack.audit.domain.exception.BadRequestException;
import com.eudistack.audit.domain.model.ActorKind;
import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.model.EventPage;
import com.eudistack.audit.domain.model.Power;
import com.eudistack.audit.domain.model.QuerySpec;
import com.eudistack.audit.domain.model.ReadScope;
import com.eudistack.audit.domain.model.SortOrder;
import com.eudistack.audit.domain.model.StoredEvent;
import com.eudistack.audit.domain.model.TenantRef;
import com.eudistack.audit.domain.policy.CursorCodec;
import com.eudistack.audit.domain.policy.SelfEventFactory;
import com.eudistack.audit.domain.port.in.ListRequest;
import com.eudistack.audit.domain.port.in.QueryContext;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;
import com.eudistack.audit.domain.port.out.CatalogueResolverPort;
import com.eudistack.audit.domain.port.out.EventQueryPort;
import com.eudistack.audit.domain.port.out.TenantDirectoryPort;
import com.eudistack.audit.support.TestEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ListEventsWorkflow: order of the listing")
class ListEventsWorkflowTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String FROM = "2026-10-12T00:00:00Z";
    private static final String TO = "2026-10-19T00:00:00Z";
    private static final String HOST = "red-logistica";
    private static final CursorCodec CODEC = new CursorCodec("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final Caller READER = new Caller("auditor", ActorKind.PERSON, null, null, "VATES-B1", "red-logistica",
            null, List.of(new Power("organization", "RED-LOGISTICA", "Audit", List.of("Read"))));
    private static final Caller DOMAIN_READER = new Caller("acme", ActorKind.PERSON, null, null, "VATES-A1",
            "red-logistica", null, List.of(new Power("domain", "RED-LOGISTICA", "Audit", List.of("Read"))));
    private static final Caller ADMIN = new Caller("ops", ActorKind.SYSTEM, null, null, "EUDISTACK", "platform", null,
            List.of(new Power("organization", "EUDISTACK", "System", List.of("Administration"))));

    private final List<QuerySpec> asked = new ArrayList<>();
    private List<StoredEvent> rows = List.of();
    private final ListEventsWorkflow workflow = new ListEventsWorkflow(
            new QueryAccessGuard(directory(), (target, event) -> { }, new SelfEventFactory(CLOCK, "2.2.0")),
            store(), catalogue(), CODEC, AuditTelemetryPort.noop(), CLOCK);

    private QuerySpec lastAsked() {
        return asked.getLast();
    }

    private EventPage list(Caller caller, String host, ListRequest request) {
        return workflow.list(new QueryContext(caller, host, "trace-1"), request);
    }

    private String firstCursor(String order, Caller caller) {
        rows = List.of(row("red_logistica", "H1", NOW.minusSeconds(3600)), row("red_logistica", "H2", NOW.minusSeconds(7200)));
        return list(caller, HOST, request(order, null, "issuance:a", FROM, TO, null)).nextCursor();
    }

    @Test
    @DisplayName("list_withoutOrder_asksTheStoreAscending")
    void list_withoutOrder_asksTheStoreAscending() {
        // Arrange
        ListRequest request = request(null, null, null, FROM, TO, null);

        // Act
        list(READER, HOST, request);

        // Assert
        assertThat(lastAsked().order()).isEqualTo(SortOrder.ASC);
    }

    @Test
    @DisplayName("list_orderDesc_asksTheStoreDescending")
    void list_orderDesc_asksTheStoreDescending() {
        // Arrange
        ListRequest request = request("desc", null, null, FROM, TO, null);

        // Act
        list(READER, HOST, request);

        // Assert
        assertThat(lastAsked().order()).isEqualTo(SortOrder.DESC);
    }

    @ParameterizedTest(name = "order \"{0}\" is a BAD_REQUEST")
    @ValueSource(strings = {"sideways", "DESC", ""})
    @DisplayName("list_unknownOrder_isBadRequest")
    void list_unknownOrder_isBadRequest(String order) {
        // Arrange
        ListRequest request = request(order, null, null, FROM, TO, null);

        // Act + Assert
        assertThatThrownBy(() -> list(READER, HOST, request)).isInstanceOf(BadRequestException.class);
    }

    @ParameterizedTest(name = "the next cursor keeps order {0}")
    @ValueSource(strings = {"asc", "desc"})
    @DisplayName("list_morePages_nextCursorKeepsTheOrderOfTheFirstPage")
    void list_morePages_nextCursorKeepsTheOrderOfTheFirstPage(String order) {
        // Arrange + Act
        String cursor = firstCursor(order, READER);

        // Assert
        assertThat(CODEC.decode(cursor).order()).isEqualTo(SortOrder.fromWire(order).orElseThrow());
    }

    @Test
    @DisplayName("list_cursorOfTheSameListing_continuesAfterItsPosition")
    void list_cursorOfTheSameListing_continuesAfterItsPosition() {
        // Arrange
        String cursor = firstCursor("desc", READER);
        ListRequest next = request("desc", cursor, "issuance:a", FROM, TO, null);

        // Act
        list(READER, HOST, next);

        // Assert
        assertThat(lastAsked().after()).isEqualTo(CODEC.decode(cursor).position());
        assertThat(lastAsked().order()).isEqualTo(SortOrder.DESC);
    }

    @ParameterizedTest(name = "a cursor used with {0} is a BAD_REQUEST")
    @MethodSource("requestsThatDoNotBelongToTheCursor")
    @DisplayName("list_cursorOfAnotherListing_isBadRequest")
    void list_cursorOfAnotherListing_isBadRequest(String difference, Function<String, ListRequest> request, Caller caller) {
        // Arrange
        String cursor = firstCursor("desc", READER);
        ListRequest other = request.apply(cursor);

        // Act + Assert
        assertThatThrownBy(() -> list(caller, HOST, other)).isInstanceOf(BadRequestException.class);
    }

    static Stream<Arguments> requestsThatDoNotBelongToTheCursor() {
        Function<String, ListRequest> anotherOrder = c -> request("asc", c, "issuance:a", FROM, TO, null);
        Function<String, ListRequest> noOrder = c -> request(null, c, "issuance:a", FROM, TO, null);
        Function<String, ListRequest> anotherStart = c -> request("desc", c, "issuance:a", "2026-10-13T00:00:00Z", TO, null);
        Function<String, ListRequest> anotherEnd = c -> request("desc", c, "issuance:a", FROM, "2026-10-18T00:00:00Z", null);
        Function<String, ListRequest> anotherFilter = c -> request("desc", c, "issuance:b", FROM, TO, null);
        Function<String, ListRequest> sameQuery = c -> request("desc", c, "issuance:a", FROM, TO, null);
        return Stream.of(
                Arguments.of("another order", anotherOrder, READER),
                Arguments.of("no order, which is ascending", noOrder, READER),
                Arguments.of("another start of the period", anotherStart, READER),
                Arguments.of("another end of the period", anotherEnd, READER),
                Arguments.of("other filters", anotherFilter, READER),
                Arguments.of("another scope", sameQuery, DOMAIN_READER));
    }

    @Test
    @DisplayName("list_administratorOnAllTenants_nextCursorKeepsTheSchemaOfTheLastEvent")
    void list_administratorOnAllTenants_nextCursorKeepsTheSchemaOfTheLastEvent() {
        // Arrange
        rows = List.of(row("sandbox", "H1", NOW.minusSeconds(3600)), row("red_logistica", "H2", NOW.minusSeconds(7200)));
        ListRequest request = request("desc", null, null, FROM, TO, "all");

        // Act
        EventPage page = list(ADMIN, "platform", request);

        // Assert
        assertThat(CODEC.decode(page.nextCursor()).position().schema()).isEqualTo("sandbox");
        assertThat(page.events()).hasSize(1);
    }

    private static ListRequest request(String order, String cursor, String resource, String from, String to, String tenant) {
        return new ListRequest(from, to, List.of(), List.of(), null, resource, null, null, null, null, tenant, "1",
                cursor, order, JsonNodeFactory.instance.objectNode());
    }

    private static StoredEvent row(String schema, String holder, Instant at) {
        ObjectNode json = TestEvents.issuanceCompleted("red-logistica", "corr-" + UUID.randomUUID(), holder);
        json.put("occurred_at", at.toString());
        return new StoredEvent(AuditEvent.fromValidatedJson(json).withRecordedAt(NOW), schema);
    }

    private EventQueryPort store() {
        return new EventQueryPort() {
            @Override
            public List<StoredEvent> find(ReadScope scope, QuerySpec spec) {
                asked.add(spec);
                return rows;
            }

            @Override
            public Optional<StoredEvent> findById(ReadScope scope, UUID eventId, Instant onlineFrom) {
                return Optional.empty();
            }
        };
    }

    private static TenantDirectoryPort directory() {
        return new TenantDirectoryPort() {
            @Override
            public Optional<TenantRef> find(String tenantId) {
                return Optional.of("platform".equals(tenantId) ? TenantRef.PLATFORM : TenantRef.forNewTenant(tenantId));
            }

            @Override
            public List<TenantRef> tenants() {
                return List.of();
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
