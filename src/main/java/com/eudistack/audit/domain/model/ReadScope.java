package com.eudistack.audit.domain.model;

import java.util.Objects;

/**
 * What a query may see (FR-15, PRD §5).
 */
public sealed interface ReadScope {

    /**
     * Tenant where the {@code AuditTrailQueried} of a query on this scope is stored (design §3.4.5).
     */
    TenantRef auditTarget();

    /**
     * Value of {@code AuditTrailQueried.details.queried_scope}.
     */
    String label();

    /**
     * Name of the scope for spans: {@code organization}, {@code domain}, {@code platform} or {@code all}.
     */
    String kind();

    /**
     * One tenant (or {@code platform}). With {@code organization} set, only the events of that
     * organization ({@code domain} reader): the filter comes from the attestation, never from the query.
     */
    record SingleTenant(TenantRef tenant, String organization) implements ReadScope {

        public SingleTenant {
            Objects.requireNonNull(tenant, "tenant");
        }

        @Override
        public TenantRef auditTarget() {
            return tenant;
        }

        @Override
        public String label() {
            return organization == null ? tenant.tenantId() : tenant.tenantId() + "/" + organization;
        }

        @Override
        public String kind() {
            if (tenant.isPlatform()) {
                return "platform";
            }
            return organization == null ? "organization" : "domain";
        }
    }

    /**
     * Every tenant and {@code platform}. Platform administrator only.
     */
    record AllTenants() implements ReadScope {

        @Override
        public TenantRef auditTarget() {
            return TenantRef.PLATFORM;
        }

        @Override
        public String label() {
            return TenantRef.ALL;
        }

        @Override
        public String kind() {
            return TenantRef.ALL;
        }
    }
}
