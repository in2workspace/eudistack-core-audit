package com.eudistack.audit.infrastructure.persistence.tenant;

import com.eudistack.audit.domain.model.TenantRef;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Routing of a statement to a tenant schema (AD-2, {@code spring-conventions} "Multi-tenant persistence").
 *
 * <p>{@code set_config('search_path', …, true)}: the third argument limits the change to the current
 * transaction, so it must always run inside one. The name comes from {@link TenantRef}, which only
 * holds names read from {@code tenant_directory} (or {@code platform}) and validated against
 * {@code ^[a-z][a-z0-9_]{0,62}$}; it is still quoted.
 */
public final class SearchPath {

    private SearchPath() {
    }

    public static void setLocal(JdbcClient jdbc, String schemaName) {
        TenantRef.requireValidSchemaName(schemaName);
        jdbc.sql("SELECT set_config('search_path', :path, true)")
                .param("path", "\"" + schemaName + "\"")
                .query(String.class)
                .single();
    }
}
