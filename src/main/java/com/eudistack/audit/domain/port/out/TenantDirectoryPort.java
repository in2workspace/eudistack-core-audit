package com.eudistack.audit.domain.port.out;

import com.eudistack.audit.domain.model.TenantRef;

import java.util.List;
import java.util.Optional;

/**
 * {@code platform.tenant_directory}: the only source of schema names. A tenant that is not here does
 * not exist ({@code unknown_tenant}).
 */
public interface TenantDirectoryPort {

    /**
     * @param tenantId tenant identifier; {@code platform} resolves to {@link TenantRef#PLATFORM}
     */
    Optional<TenantRef> find(String tenantId);

    /**
     * Every provisioned tenant, {@code platform} excluded.
     */
    List<TenantRef> tenants();
}
