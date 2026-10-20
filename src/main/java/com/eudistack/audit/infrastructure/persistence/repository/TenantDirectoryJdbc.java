package com.eudistack.audit.infrastructure.persistence.repository;

import com.eudistack.audit.domain.exception.ServiceUnavailableException;
import com.eudistack.audit.domain.model.TenantRef;
import com.eudistack.audit.domain.port.out.TenantDirectoryPort;
import com.eudistack.audit.domain.port.out.TenantRegistrationPort;
import com.eudistack.audit.infrastructure.persistence.tenant.JdbcPool;
import org.springframework.dao.DataAccessException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code platform.tenant_directory}. Rows are never deleted or changed, so a found tenant is cached;
 * a miss always goes to the database, so a tenant provisioned a second ago is already known.
 */
public final class TenantDirectoryJdbc implements TenantDirectoryPort, TenantRegistrationPort {

    private final JdbcPool pool;
    private final Map<String, TenantRef> cache = new ConcurrentHashMap<>();

    public TenantDirectoryJdbc(JdbcPool pool) {
        this.pool = pool;
    }

    @Override
    public Optional<TenantRef> find(String tenantId) {
        String id = TenantRef.normalise(tenantId);
        if (id == null || id.isEmpty()) {
            return Optional.empty();
        }
        if (TenantRef.PLATFORM_ID.equals(id)) {
            return Optional.of(TenantRef.PLATFORM);
        }
        TenantRef cached = cache.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            Optional<TenantRef> found = pool.jdbc()
                    .sql("SELECT tenant_id, schema_name FROM platform.tenant_directory WHERE tenant_id = :id")
                    .param("id", id)
                    .query((rs, row) -> new TenantRef(rs.getString("tenant_id"), rs.getString("schema_name")))
                    .optional();
            found.ifPresent(ref -> cache.put(id, ref));
            return found;
        } catch (DataAccessException e) {
            throw new ServiceUnavailableException("The tenant directory is not available", e);
        }
    }

    @Override
    public List<TenantRef> tenants() {
        try {
            return pool.jdbc()
                    .sql("SELECT tenant_id, schema_name FROM platform.tenant_directory ORDER BY schema_name")
                    .query((rs, row) -> new TenantRef(rs.getString("tenant_id"), rs.getString("schema_name")))
                    .list();
        } catch (DataAccessException e) {
            throw new ServiceUnavailableException("The tenant directory is not available", e);
        }
    }

    @Override
    public void register(TenantRef tenant) {
        pool.jdbc()
                .sql("""
                        INSERT INTO platform.tenant_directory (tenant_id, schema_name)
                        VALUES (:tenant_id, :schema_name)
                        ON CONFLICT (tenant_id) DO NOTHING
                        """)
                .param("tenant_id", tenant.tenantId())
                .param("schema_name", tenant.schemaName())
                .update();
    }
}
