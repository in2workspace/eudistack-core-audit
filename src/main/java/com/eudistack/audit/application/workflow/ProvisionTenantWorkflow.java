package com.eudistack.audit.application.workflow;

import com.eudistack.audit.domain.model.TenantRef;
import com.eudistack.audit.domain.port.out.SchemaMigratorPort;
import com.eudistack.audit.domain.port.out.TenantRegistrationPort;

import java.util.Optional;

/**
 * Tenant provisioning (AD-3): the platform onboarding runs it as a one-shot task with
 * {@code audit.role=provisioner}, under {@code audit_migrator}, never under a runtime role.
 *
 * <p>Order: {@code platform} migrations, the tenant schema migrations (table, indexes, triggers, grants)
 * and, only when all of them succeeded, the row in {@code tenant_directory}. A schema half created is
 * never registered, so its events keep getting {@code unknown_tenant}. Idempotent.
 */
public final class ProvisionTenantWorkflow {

    private final SchemaMigratorPort migrator;
    private final TenantRegistrationPort registration;

    public ProvisionTenantWorkflow(SchemaMigratorPort migrator, TenantRegistrationPort registration) {
        this.migrator = migrator;
        this.registration = registration;
    }

    public void provisionPlatform() {
        migrator.migratePlatform();
    }

    public TenantRef provision(String tenantId) {
        migrator.migratePlatform();
        TenantRef tenant = TenantRef.forNewTenant(tenantId);
        Optional<TenantRef> existing = registration.find(tenant.tenantId());
        if (existing.isPresent() && !existing.get().schemaName().equals(tenant.schemaName())) {
            throw new IllegalStateException("Tenant " + tenant.tenantId() + " is already registered with schema "
                    + existing.get().schemaName());
        }
        migrator.migrateTenant(tenant.schemaName());
        if (existing.isEmpty()) {
            registration.register(tenant);
        }
        return tenant;
    }
}
