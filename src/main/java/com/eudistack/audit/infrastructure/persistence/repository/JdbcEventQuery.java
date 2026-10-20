package com.eudistack.audit.infrastructure.persistence.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.eudistack.audit.domain.model.CursorPosition;
import com.eudistack.audit.domain.model.QuerySpec;
import com.eudistack.audit.domain.model.ReadScope;
import com.eudistack.audit.domain.model.Result;
import com.eudistack.audit.domain.model.StoredEvent;
import com.eudistack.audit.domain.model.TenantRef;
import com.eudistack.audit.domain.port.out.EventQueryPort;
import com.eudistack.audit.domain.port.out.TenantDirectoryPort;
import com.eudistack.audit.infrastructure.persistence.tenant.JdbcPool;
import com.eudistack.audit.infrastructure.persistence.tenant.SearchPath;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read side of the {@code reader} profile ({@code audit_reader}) with plain JDBC (decision D-3: no JPA).
 *
 * <p>A single-tenant query sets the transaction's {@code search_path} to the tenant schema and filters
 * with bound parameters; a {@code domain} reader always gets {@code organization = :caller}. The
 * {@code tenant=all} query (platform administrator only) reads {@code platform} and every schema of
 * {@code tenant_directory}, one after the other in the same transaction, and merges them by
 * {@code (occurred_at, event_id, schema)} (design §3.4.2).
 */
public final class JdbcEventQuery implements EventQueryPort {

    private final JdbcPool pool;
    private final EventSql sql;
    private final TenantDirectoryPort directory;
    private final ObjectMapper mapper;

    public JdbcEventQuery(JdbcPool pool, EventSql sql, TenantDirectoryPort directory, ObjectMapper mapper) {
        this.pool = pool;
        this.sql = sql;
        this.directory = directory;
        this.mapper = mapper;
    }

    @Override
    public List<StoredEvent> find(ReadScope scope, QuerySpec spec) {
        return pool.inTransaction(jdbc -> switch (scope) {
            case ReadScope.SingleTenant single -> findIn(jdbc, single.tenant().schemaName(), spec, spec.after());
            case ReadScope.AllTenants all -> findInAll(jdbc, spec);
        });
    }

    @Override
    public Optional<StoredEvent> findById(ReadScope scope, UUID eventId, Instant onlineFrom) {
        return pool.inTransaction(jdbc -> {
            for (TenantRef tenant : schemasOf(scope)) {
                SearchPath.setLocal(jdbc, tenant.schemaName());
                StringBuilder where = new StringBuilder("event_id = :event_id AND occurred_at >= :online_from");
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("event_id", eventId);
                params.put("online_from", utc(onlineFrom));
                if (scope instanceof ReadScope.SingleTenant single && single.organization() != null) {
                    where.append(" AND organization = :scope_organization");
                    params.put("scope_organization", single.organization());
                }
                Optional<StoredEvent> found = jdbc.sql("SELECT " + EventSql.COLUMNS + " FROM audit_event WHERE " + where)
                        .params(params)
                        .query(sql.rowMapper())
                        .optional()
                        .map(event -> new StoredEvent(event, tenant.schemaName()));
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        });
    }

    private List<StoredEvent> findInAll(JdbcClient jdbc, QuerySpec spec) {
        List<StoredEvent> merged = new ArrayList<>();
        for (TenantRef tenant : schemasOf(new ReadScope.AllTenants())) {
            merged.addAll(findIn(jdbc, tenant.schemaName(), spec, spec.after()));
        }
        merged.sort((a, b) -> a.position().compareTo(b.position()));
        return merged.size() > spec.limit() + 1 ? List.copyOf(merged.subList(0, spec.limit() + 1)) : merged;
    }

    private List<StoredEvent> findIn(JdbcClient jdbc, String schema, QuerySpec spec, CursorPosition after) {
        SearchPath.setLocal(jdbc, schema);
        StringBuilder where = new StringBuilder("occurred_at >= :from AND occurred_at < :to");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", utc(spec.from()));
        params.put("to", utc(spec.to()));
        if (!spec.eventTypes().isEmpty()) {
            where.append(" AND event_type IN (:event_types)");
            params.put("event_types", spec.eventTypes());
        }
        if (!spec.results().isEmpty()) {
            where.append(" AND result IN (:results)");
            params.put("results", spec.results().stream().map(Result::wireValue).toList());
        }
        optional(where, params, "correlation_id", spec.correlationId());
        optional(where, params, "resource", spec.resource());
        optional(where, params, "actor_id", spec.actorId());
        optional(where, params, "organization", spec.organization());
        if (spec.containment() != null) {
            where.append(" AND details @> CAST(:containment AS jsonb)");
            params.put("containment", json(spec.containment()));
        }
        if (after != null) {
            // Within one schema the order is (occurred_at, event_id); across schemas the schema breaks ties.
            boolean inclusive = after.schema() != null && schema.compareTo(after.schema()) > 0;
            where.append(inclusive ? " AND (occurred_at, event_id) >= (:after_at, :after_id)"
                    : " AND (occurred_at, event_id) > (:after_at, :after_id)");
            params.put("after_at", utc(after.occurredAt()));
            params.put("after_id", after.eventId());
        }
        params.put("limit", spec.limit() + 1);
        return jdbc.sql("SELECT " + EventSql.COLUMNS + " FROM audit_event WHERE " + where
                        + " ORDER BY occurred_at, event_id LIMIT :limit")
                .params(params)
                .query(sql.rowMapper())
                .list()
                .stream()
                .map(event -> new StoredEvent(event, schema))
                .toList();
    }

    private List<TenantRef> schemasOf(ReadScope scope) {
        if (scope instanceof ReadScope.SingleTenant single) {
            return List.of(single.tenant());
        }
        List<TenantRef> schemas = new ArrayList<>();
        schemas.add(TenantRef.PLATFORM);
        schemas.addAll(directory.tenants());
        return schemas;
    }

    private static void optional(StringBuilder where, Map<String, Object> params, String column, String value) {
        if (value != null) {
            where.append(" AND ").append(column).append(" = :").append(column);
            params.put(column, value);
        }
    }

    private String json(Object node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
