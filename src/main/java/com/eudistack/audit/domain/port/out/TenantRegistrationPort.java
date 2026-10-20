package com.eudistack.audit.domain.port.out;

import com.eudistack.audit.domain.model.TenantRef;

import java.util.Optional;

/**
 * Registration of a provisioned tenant in {@code platform.tenant_directory} ({@code audit_migrator}).
 */
public interface TenantRegistrationPort {

    Optional<TenantRef> find(String tenantId);

    void register(TenantRef tenant);
}
