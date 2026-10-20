package com.eudistack.audit.domain.policy;

import com.eudistack.audit.domain.model.Caller;

/**
 * Powers of the audit API (FR-02, PRD §2).
 *
 * <ul>
 *   <li>Write: {@code organization · EUDISTACK · Audit · Write}.</li>
 *   <li>Read: {@code organization · <tenant> · Audit · Read}, {@code domain · <tenant> · Audit · Read}
 *       or {@code organization · EUDISTACK · System · Administration}.</li>
 * </ul>
 *
 * <p>The two faces are exclusive: a caller holding a write power cannot read, and one holding a read
 * power cannot write ("quien escribe no puede leer y quien lee no puede escribir").
 */
public final class AccessPolicy {

    public static final String PLATFORM_DOMAIN = "EUDISTACK";
    static final String ORGANIZATION = "organization";
    static final String DOMAIN = "domain";
    static final String AUDIT = "Audit";
    static final String READ = "Read";
    static final String WRITE = "Write";

    private AccessPolicy() {
    }

    public static boolean canWrite(Caller caller) {
        return caller.powers().stream().anyMatch(p -> p.matches(ORGANIZATION, PLATFORM_DOMAIN, AUDIT, WRITE))
                && !holdsAnyRead(caller);
    }

    public static boolean isPlatformAdministrator(Caller caller) {
        return caller.powers().stream()
                .anyMatch(p -> p.matches(ORGANIZATION, PLATFORM_DOMAIN, "System", "Administration"));
    }

    public static boolean holdsAnyWrite(Caller caller) {
        return caller.powers().stream().anyMatch(p -> p.grants(AUDIT, WRITE));
    }

    public static boolean holdsAnyRead(Caller caller) {
        return isPlatformAdministrator(caller) || caller.powers().stream().anyMatch(p -> p.grants(AUDIT, READ));
    }

    public static boolean readsWholeTenant(Caller caller, String tenantId) {
        return caller.powers().stream().anyMatch(p -> p.matches(ORGANIZATION, tenantId, AUDIT, READ));
    }

    public static boolean readsOwnOrganization(Caller caller, String tenantId) {
        return caller.powers().stream().anyMatch(p -> p.matches(DOMAIN, tenantId, AUDIT, READ));
    }
}
