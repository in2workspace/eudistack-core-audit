package com.eudistack.audit.domain.policy;

import com.eudistack.audit.domain.model.ActorKind;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.model.Power;
import com.eudistack.audit.domain.model.ReadScope;
import com.eudistack.audit.domain.model.TenantRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ScopePolicyTest {

    private static final TenantRef RED = new TenantRef("red-logistica", "red_logistica");
    private static final TenantRef SANDBOX = new TenantRef("sandbox", "sandbox");
    private static final Function<String, Optional<TenantRef>> DIRECTORY =
            id -> Optional.ofNullable(Map.of("red-logistica", RED, "sandbox", SANDBOX).get(id));

    private static Caller caller(String tenant, String organization, Power... powers) {
        return new Caller("user-1", ActorKind.PERSON, "Ana", null, organization, tenant, null, List.of(powers));
    }

    private static Power power(String type, String domain, String function, String action) {
        return new Power(type, domain, function, List.of(action));
    }

    private static ScopePolicy.Decision resolve(Caller caller, TenantRef host, String tenantParam) {
        return ScopePolicy.resolve(caller, Optional.ofNullable(host), tenantParam, DIRECTORY);
    }

    @Test
    void organizationReader_seesWholeTenant_domainIgnoringCase() {
        Caller caller = caller("red-logistica", "VATES-B1", power("organization", "RED-LOGISTICA", "Audit", "Read"));

        assertThat(resolve(caller, RED, null)).isEqualTo(new ScopePolicy.Granted(new ReadScope.SingleTenant(RED, null)));
    }

    @Test
    void domainReader_seesOnlyOwnOrganization() {
        Caller caller = caller("red-logistica", "VATES-SUR", power("domain", "RED-LOGISTICA", "Audit", "Read"));

        assertThat(resolve(caller, RED, null))
                .isEqualTo(new ScopePolicy.Granted(new ReadScope.SingleTenant(RED, "VATES-SUR")));
    }

    @Test
    void readerOfAnotherTenant_isTenantMismatch() {
        Caller caller = caller("sandbox", "VATES-B1", power("organization", "SANDBOX", "Audit", "Read"));

        assertThat(resolve(caller, RED, null)).isInstanceOfSatisfying(ScopePolicy.Denied.class, d -> {
            assertThat(d.reason()).isEqualTo(ScopePolicy.DenialReason.TENANT_MISMATCH);
            assertThat(d.auditTarget()).isEqualTo(RED);
        });
    }

    @Test
    void powerOfAnotherTenant_isPermissionMissing() {
        Caller caller = caller("red-logistica", "VATES-B1", power("organization", "SANDBOX", "Audit", "Read"));

        assertThat(resolve(caller, RED, null)).isInstanceOfSatisfying(ScopePolicy.Denied.class,
                d -> assertThat(d.reason()).isEqualTo(ScopePolicy.DenialReason.PERMISSION_MISSING));
    }

    @Test
    void writer_cannotRead() {
        Caller caller = caller("red-logistica", "VATES-B1", power("organization", "RED-LOGISTICA", "Audit", "Read"),
                power("organization", "EUDISTACK", "Audit", "Write"));

        assertThat(resolve(caller, RED, null)).isInstanceOfSatisfying(ScopePolicy.Denied.class,
                d -> assertThat(d.reason()).isEqualTo(ScopePolicy.DenialReason.PERMISSION_MISSING));
    }

    @Test
    void unresolvedHost_isTenantNotResolved_auditedInPlatform() {
        Caller caller = caller("red-logistica", "VATES-B1", power("organization", "RED-LOGISTICA", "Audit", "Read"));

        assertThat(resolve(caller, null, null)).isInstanceOfSatisfying(ScopePolicy.Denied.class, d -> {
            assertThat(d.reason()).isEqualTo(ScopePolicy.DenialReason.TENANT_NOT_RESOLVED);
            assertThat(d.auditTarget()).isEqualTo(TenantRef.PLATFORM);
        });
    }

    @Test
    void tenantParameter_fromNonAdministrator_isTenantMismatch() {
        Caller caller = caller("red-logistica", "VATES-B1", power("organization", "RED-LOGISTICA", "Audit", "Read"));

        assertThat(resolve(caller, RED, "sandbox")).isInstanceOfSatisfying(ScopePolicy.Denied.class,
                d -> assertThat(d.reason()).isEqualTo(ScopePolicy.DenialReason.TENANT_MISMATCH));
    }

    @Test
    void administrator_choosesTheScope() {
        Caller admin = caller("platform", "VATES-EUDISTACK", power("organization", "EUDISTACK", "System",
                "Administration"));

        assertThat(resolve(admin, TenantRef.PLATFORM, null))
                .isEqualTo(new ScopePolicy.Granted(new ReadScope.SingleTenant(TenantRef.PLATFORM, null)));
        assertThat(resolve(admin, TenantRef.PLATFORM, "all"))
                .isEqualTo(new ScopePolicy.Granted(new ReadScope.AllTenants()));
        assertThat(resolve(admin, TenantRef.PLATFORM, "SANDBOX"))
                .isEqualTo(new ScopePolicy.Granted(new ReadScope.SingleTenant(SANDBOX, null)));
        assertThat(resolve(admin, TenantRef.PLATFORM, "nope")).isInstanceOfSatisfying(ScopePolicy.Denied.class,
                d -> assertThat(d.reason()).isEqualTo(ScopePolicy.DenialReason.TENANT_NOT_RESOLVED));
    }

    @Test
    void administrator_fromTenantHost_isTenantMismatch() {
        Caller admin = caller("platform", "VATES-EUDISTACK", power("organization", "EUDISTACK", "System",
                "Administration"));

        assertThat(resolve(admin, RED, "all")).isInstanceOfSatisfying(ScopePolicy.Denied.class,
                d -> assertThat(d.reason()).isEqualTo(ScopePolicy.DenialReason.TENANT_MISMATCH));
    }

    @Test
    void platformScope_onlyForTheAdministrator() {
        Caller caller = caller("platform", "VATES-X", power("organization", "PLATFORM", "Audit", "Read"));

        assertThat(resolve(caller, TenantRef.PLATFORM, null)).isInstanceOfSatisfying(ScopePolicy.Denied.class,
                d -> assertThat(d.reason()).isEqualTo(ScopePolicy.DenialReason.PERMISSION_MISSING));
    }

    @Test
    void accessPolicy_writeRequiresPlatformDomainAndNoRead() {
        Caller writer = caller("platform", "VATES-E", power("organization", "EUDISTACK", "Audit", "Write"));
        Caller tenantWriter = caller("sandbox", "VATES-E", power("organization", "SANDBOX", "Audit", "Write"));
        Caller both = caller("platform", "VATES-E", power("organization", "EUDISTACK", "Audit", "Write"),
                power("organization", "SANDBOX", "Audit", "Read"));

        assertThat(AccessPolicy.canWrite(writer)).isTrue();
        assertThat(AccessPolicy.canWrite(tenantWriter)).isFalse();
        assertThat(AccessPolicy.canWrite(both)).isFalse();
    }
}
