package com.eudistack.audit.domain.policy;

import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.model.ReadScope;
import com.eudistack.audit.domain.model.TenantRef;

import java.util.Optional;
import java.util.function.Function;

/**
 * Decides what a query may see (FR-15, PRD §5 "Tenant y ámbito").
 *
 * <ul>
 *   <li>The tenant comes from the request host and must be the tenant of the caller's attestation.</li>
 *   <li>{@code organization · <tenant> · Audit · Read} sees the whole tenant; {@code domain · <tenant> ·
 *       Audit · Read} only the events whose {@code organization} is the caller's.</li>
 *   <li>Only the platform administrator, from the platform tenant, sends {@code tenant}: a tenant,
 *       {@code platform} or {@code all}. Without it, the administrator sees {@code platform}.</li>
 *   <li>A caller holding a write power cannot read (FR-02).</li>
 * </ul>
 */
public final class ScopePolicy {

    private ScopePolicy() {
    }

    public enum DenialReason {
        PERMISSION_MISSING("PermissionMissing"),
        TENANT_MISMATCH("TenantMismatch"),
        TENANT_NOT_RESOLVED("TenantNotResolved");

        private final String catalogueReason;

        DenialReason(String catalogueReason) {
            this.catalogueReason = catalogueReason;
        }

        public String catalogueReason() {
            return catalogueReason;
        }
    }

    public sealed interface Decision {
    }

    public record Granted(ReadScope scope) implements Decision {
    }

    /**
     * @param reason      why
     * @param auditTarget tenant where the denied {@code AuditTrailQueried} is stored (design §3.4.5)
     * @param label       {@code queried_scope} of that event
     */
    public record Denied(DenialReason reason, TenantRef auditTarget, String label) implements Decision {
    }

    /**
     * @param caller      authenticated caller
     * @param hostTenant  tenant resolved from the host, empty when it could not be resolved
     * @param tenantParam {@code tenant} query parameter, or {@code null}
     * @param directory   lookup of a tenant identifier in {@code tenant_directory}
     */
    public static Decision resolve(Caller caller, Optional<TenantRef> hostTenant, String tenantParam,
                                   Function<String, Optional<TenantRef>> directory) {
        if (hostTenant.isEmpty()) {
            return new Denied(DenialReason.TENANT_NOT_RESOLVED, TenantRef.PLATFORM, TenantRef.PLATFORM_ID);
        }
        TenantRef host = hostTenant.get();
        if (caller.tenant() == null || !caller.tenant().equalsIgnoreCase(host.tenantId())) {
            return denied(DenialReason.TENANT_MISMATCH, host);
        }
        if (AccessPolicy.holdsAnyWrite(caller)) {
            return denied(DenialReason.PERMISSION_MISSING, host);
        }
        boolean administrator = host.isPlatform() && AccessPolicy.isPlatformAdministrator(caller);
        if (tenantParam != null) {
            return administrator ? administratorScope(TenantRef.normalise(tenantParam), directory)
                    : denied(DenialReason.TENANT_MISMATCH, host);
        }
        if (administrator) {
            return new Granted(new ReadScope.SingleTenant(TenantRef.PLATFORM, null));
        }
        if (host.isPlatform()) {
            return denied(DenialReason.PERMISSION_MISSING, host);
        }
        if (AccessPolicy.readsWholeTenant(caller, host.tenantId())) {
            return new Granted(new ReadScope.SingleTenant(host, null));
        }
        if (AccessPolicy.readsOwnOrganization(caller, host.tenantId()) && caller.organization() != null) {
            return new Granted(new ReadScope.SingleTenant(host, caller.organization()));
        }
        return denied(DenialReason.PERMISSION_MISSING, host);
    }

    private static Decision administratorScope(String tenant, Function<String, Optional<TenantRef>> directory) {
        if (TenantRef.ALL.equals(tenant)) {
            return new Granted(new ReadScope.AllTenants());
        }
        if (TenantRef.PLATFORM_ID.equals(tenant)) {
            return new Granted(new ReadScope.SingleTenant(TenantRef.PLATFORM, null));
        }
        return directory.apply(tenant)
                .<Decision>map(ref -> new Granted(new ReadScope.SingleTenant(ref, null)))
                .orElseGet(() -> new Denied(DenialReason.TENANT_NOT_RESOLVED, TenantRef.PLATFORM, tenant));
    }

    private static Denied denied(DenialReason reason, TenantRef host) {
        return new Denied(reason, host, host.tenantId());
    }
}
