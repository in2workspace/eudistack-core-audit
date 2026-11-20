package com.eudistack.audit.infrastructure.persistence.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.CursorPosition;
import com.eudistack.audit.domain.model.QuerySpec;
import com.eudistack.audit.domain.model.ReadScope;
import com.eudistack.audit.domain.model.SortOrder;
import com.eudistack.audit.domain.model.StoredEvent;
import com.eudistack.audit.domain.model.TenantRef;
import com.eudistack.audit.domain.port.out.TenantDirectoryPort;
import com.eudistack.audit.infrastructure.persistence.tenant.JdbcPool;
import com.eudistack.audit.support.TestEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The statement the store sends for each order and cursor, without a database: the rows it returns are
 * checked by {@code ListEventsIT} against PostgreSQL.
 */
@DisplayName("JdbcEventQuery: order and cursor of the statement")
class JdbcEventQueryTest {

    private static final Instant FROM = Instant.parse("2026-10-12T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-19T00:00:00Z");
    private static final TenantRef RED = TenantRef.forNewTenant("red-logistica");
    private static final TenantRef SANDBOX = TenantRef.forNewTenant("sandbox");
    private static final TenantRef ZETA = TenantRef.forNewTenant("zeta");

    private final JdbcPool pool = mock(JdbcPool.class);
    private final JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    private final TenantDirectoryPort directory = new TenantDirectoryPort() {
        @Override
        public java.util.Optional<TenantRef> find(String tenantId) {
            return java.util.Optional.empty();
        }

        @Override
        public List<TenantRef> tenants() {
            return List.of(RED, SANDBOX, ZETA);
        }
    };
    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcEventQuery query = new JdbcEventQuery(pool, new EventSql(mapper), directory, mapper);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void runTransactionsOnTheMockedClient() {
        when(pool.inTransaction(any())).thenAnswer(call -> ((Function<JdbcClient, Object>) call.getArgument(0)).apply(jdbc));
    }

    @SafeVarargs
    private void returning(List<AuditEvent>... pages) {
        when(jdbc.sql(anyString()).params(anyMap()).query(ArgumentMatchers.<RowMapper<AuditEvent>>any()).list())
                .thenReturn(pages[0], java.util.Arrays.copyOfRange(pages, 1, pages.length));
    }

    private List<String> statements() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).sql(sql.capture());
        return sql.getAllValues().stream().filter(s -> s.contains("FROM audit_event")).toList();
    }

    private static QuerySpec spec(SortOrder order, CursorPosition after) {
        return new QuerySpec(FROM, TO, List.of(), List.of(), null, null, null, null, null, order, 10, after);
    }

    private static AuditEvent event(Instant at) {
        ObjectNode json = TestEvents.issuanceCompleted("red-logistica", "corr-" + UUID.randomUUID(), "H");
        json.put("occurred_at", at.toString());
        return AuditEvent.fromValidatedJson(json).withRecordedAt(TO);
    }

    @ParameterizedTest(name = "order {0} sorts by {1}")
    @CsvSource({"ASC, 'ORDER BY occurred_at ASC, event_id ASC'", "DESC, 'ORDER BY occurred_at DESC, event_id DESC'"})
    @DisplayName("find_order_sortsInThatDirection")
    void find_order_sortsInThatDirection(SortOrder order, String expected) {
        // Arrange
        returning(List.of());

        // Act
        query.find(new ReadScope.SingleTenant(RED, null), spec(order, null));

        // Assert
        assertThat(statements()).singleElement().asString().contains(expected);
    }

    @ParameterizedTest(name = "order {0} continues with {1}")
    @CsvSource({
            "ASC, '(occurred_at, event_id) > (:after_at, :after_id)'",
            "DESC, '(occurred_at, event_id) < (:after_at, :after_id)'"})
    @DisplayName("find_cursor_continuesInTheDirectionOfTheOrder")
    void find_cursor_continuesInTheDirectionOfTheOrder(SortOrder order, String expected) {
        // Arrange
        returning(List.of());
        CursorPosition after = new CursorPosition(Instant.parse("2026-10-15T00:00:00Z"), UUID.randomUUID(), null);

        // Act
        query.find(new ReadScope.SingleTenant(RED, null), spec(order, after));

        // Assert
        assertThat(statements()).singleElement().asString().contains(expected);
    }

    @ParameterizedTest(name = "order {0}, cursor in {1}: {2}")
    @CsvSource({
            "ASC,  red_logistica, '>=' , '[red_logistica, sandbox, zeta]'",
            "DESC, zeta,          '<=' , '[platform, red_logistica, sandbox]'"})
    @DisplayName("find_allTenantsCursor_includesTheTiesOfTheSchemasThatComeAfterIt")
    void find_allTenantsCursor_includesTheTiesOfTheSchemasThatComeAfterIt(SortOrder order, String cursorSchema,
                                                                          String inclusive, String schemasThatInclude) {
        // Arrange
        returning(List.of(), List.of(), List.of(), List.of());
        CursorPosition after = new CursorPosition(Instant.parse("2026-10-15T00:00:00Z"), UUID.randomUUID(), cursorSchema);

        // Act
        query.find(new ReadScope.AllTenants(), spec(order, after));

        // Assert
        List<String> statements = statements();
        assertThat(statements).hasSize(4);
        List<String> schemas = List.of("platform", "red_logistica", "sandbox", "zeta");
        for (int i = 0; i < schemas.size(); i++) {
            boolean includesTies = statements.get(i).contains("(occurred_at, event_id) " + inclusive.strip() + " (:after_at");
            assertThat(includesTies).as("schema %s", schemas.get(i)).isEqualTo(schemasThatInclude.contains(schemas.get(i))
                    && !schemas.get(i).equals(cursorSchema));
        }
    }

    @Test
    @DisplayName("find_allTenantsDescending_mergesTheSchemasNewestFirstAndKeepsOnePageAndOne")
    void find_allTenantsDescending_mergesTheSchemasNewestFirstAndKeepsOnePageAndOne() {
        // Arrange
        Instant t1 = Instant.parse("2026-10-14T00:00:00Z");
        Instant t2 = Instant.parse("2026-10-15T00:00:00Z");
        Instant t3 = Instant.parse("2026-10-16T00:00:00Z");
        returning(List.of(event(t1)), List.of(event(t3)), List.of(event(t2)), List.of());
        QuerySpec twoPerPage = new QuerySpec(FROM, TO, List.of(), List.of(), null, null, null, null, null,
                SortOrder.DESC, 1, null);

        // Act
        List<StoredEvent> merged = query.find(new ReadScope.AllTenants(), twoPerPage);

        // Assert
        assertThat(merged).extracting(e -> e.event().occurredAt()).containsExactly(t3, t2);
    }

    @Test
    @DisplayName("find_allTenantsAscending_mergesTheSchemasOldestFirst")
    void find_allTenantsAscending_mergesTheSchemasOldestFirst() {
        // Arrange
        Instant t1 = Instant.parse("2026-10-14T00:00:00Z");
        Instant t2 = Instant.parse("2026-10-15T00:00:00Z");
        Instant t3 = Instant.parse("2026-10-16T00:00:00Z");
        returning(List.of(event(t3)), List.of(event(t1)), List.of(event(t2)), List.of());

        // Act
        List<StoredEvent> merged = query.find(new ReadScope.AllTenants(), spec(SortOrder.ASC, null));

        // Assert
        assertThat(merged).extracting(e -> e.event().occurredAt()).containsExactly(t1, t2, t3);
    }
}
