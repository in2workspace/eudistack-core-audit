package com.eudistack.audit.support;

import com.eudistack.audit.domain.exception.ServiceUnavailableException;
import com.eudistack.audit.domain.model.AuditEvent;
import com.eudistack.audit.domain.model.TenantRef;
import com.eudistack.audit.domain.policy.CanonicalJson;
import com.eudistack.audit.domain.policy.IdempotencyPolicy;
import com.eudistack.audit.domain.port.out.AuditEventRepositoryPort;
import com.eudistack.audit.domain.port.out.BatchWriter;
import com.eudistack.audit.domain.port.out.InsertOutcome;
import com.eudistack.audit.domain.port.out.SelfAuditPort;
import com.eudistack.audit.domain.port.out.TenantDirectoryPort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Transactional in-memory store: a batch either commits entirely or leaves nothing.
 */
public final class InMemoryEventStore implements AuditEventRepositoryPort, SelfAuditPort, TenantDirectoryPort {

    public record Row(AuditEvent event, byte[] hash) {
    }

    private final Map<String, Map<UUID, Row>> schemas = new HashMap<>();
    private final Map<String, TenantRef> directory = new LinkedHashMap<>();
    private boolean failCommit;

    public InMemoryEventStore withTenant(String tenantId) {
        TenantRef ref = TenantRef.forNewTenant(tenantId);
        directory.put(ref.tenantId(), ref);
        return this;
    }

    public void failNextCommit() {
        this.failCommit = true;
    }

    public synchronized List<AuditEvent> events(String schema) {
        return schemas.getOrDefault(schema, Map.of()).values().stream().map(Row::event).toList();
    }

    @Override
    public synchronized <T> T writeBatch(Function<BatchWriter, T> work) {
        Map<String, Map<UUID, Row>> pending = new HashMap<>();
        T result = work.apply(new BatchWriter() {
            @Override
            public InsertOutcome insertIfAbsent(TenantRef tenant, AuditEvent event, byte[] payloadHash) {
                Row existing = find(pending, tenant.schemaName(), event.eventId());
                if (existing != null) {
                    return IdempotencyPolicy.sameContent(existing.hash(), payloadHash)
                            ? InsertOutcome.SAME_CONTENT : InsertOutcome.DIFFERENT_CONTENT;
                }
                pending.computeIfAbsent(tenant.schemaName(), s -> new LinkedHashMap<>())
                        .put(event.eventId(), new Row(event, payloadHash));
                return InsertOutcome.INSERTED;
            }

            @Override
            public void insertPlatformEvent(AuditEvent event) {
                if (find(pending, TenantRef.PLATFORM.schemaName(), event.eventId()) == null) {
                    pending.computeIfAbsent(TenantRef.PLATFORM.schemaName(), s -> new LinkedHashMap<>())
                            .put(event.eventId(), new Row(event, CanonicalJson.sha256(event.toJson())));
                }
            }
        });
        if (failCommit) {
            failCommit = false;
            throw new ServiceUnavailableException("commit failed", null);
        }
        pending.forEach((schema, rows) -> schemas.computeIfAbsent(schema, s -> new LinkedHashMap<>()).putAll(rows));
        return result;
    }

    @Override
    public synchronized void record(TenantRef target, AuditEvent event) {
        schemas.computeIfAbsent(target.schemaName(), s -> new LinkedHashMap<>())
                .putIfAbsent(event.eventId(), new Row(event, CanonicalJson.sha256(event.toJson())));
    }

    @Override
    public Optional<TenantRef> find(String tenantId) {
        String id = TenantRef.normalise(tenantId);
        return TenantRef.PLATFORM_ID.equals(id) ? Optional.of(TenantRef.PLATFORM) : Optional.ofNullable(directory.get(id));
    }

    @Override
    public List<TenantRef> tenants() {
        return new ArrayList<>(directory.values());
    }

    private Row find(Map<String, Map<UUID, Row>> pending, String schema, UUID id) {
        Row row = pending.getOrDefault(schema, Map.of()).get(id);
        return row != null ? row : schemas.getOrDefault(schema, Map.of()).get(id);
    }
}
