package com.eudistack.audit.domain.model;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A tenant of the audit store and the PostgreSQL schema that holds its events.
 *
 * <p>The schema name always comes from {@code platform.tenant_directory} (or is {@code platform}),
 * never from a request, and matches {@code ^[a-z][a-z0-9_]{0,62}$}.
 */
public record TenantRef(String tenantId, String schemaName) {

    // Patterns first: PLATFORM below is validated by the constructor during class initialisation.
    private static final Pattern SCHEMA_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");
    private static final Pattern TENANT_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

    public static final String PLATFORM_ID = "platform";
    public static final TenantRef PLATFORM = new TenantRef(PLATFORM_ID, PLATFORM_ID);

    /** Value of the {@code tenant} query parameter that selects every tenant and {@code platform}. */
    public static final String ALL = "all";

    /** Prefix of the cold storage owned by the database access detector (FR-20); never a tenant. */
    public static final String EVIDENCE_PREFIX = "evidence";
    private static final Set<String> RESERVED = Set.of(PLATFORM_ID, ALL, EVIDENCE_PREFIX, "public",
            "information_schema");

    public TenantRef {
        Objects.requireNonNull(tenantId, "tenantId");
        requireValidSchemaName(schemaName);
    }

    public static void requireValidSchemaName(String schemaName) {
        Objects.requireNonNull(schemaName, "schemaName");
        if (!SCHEMA_NAME.matcher(schemaName).matches() || schemaName.startsWith("pg_")) {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }
    }

    public boolean isPlatform() {
        return PLATFORM_ID.equals(tenantId);
    }

    /**
     * Normalises a tenant identifier as it is compared everywhere: lower case, trimmed.
     */
    public static String normalise(String tenantId) {
        return tenantId == null ? null : tenantId.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Derives the schema of a new tenant ({@code red-logistica} → {@code red_logistica}).
     * Hyphens do not fit in an unquoted schema name, so they become underscores; the directory keeps
     * the original identifier.
     */
    public static TenantRef forNewTenant(String tenantId) {
        String id = normalise(tenantId);
        if (id == null || !TENANT_ID.matcher(id).matches() || RESERVED.contains(id)) {
            throw new IllegalArgumentException("Invalid or reserved tenant identifier: " + tenantId);
        }
        String schema = id.replace('-', '_');
        if (!Character.isLetter(schema.charAt(0))) {
            schema = "t_" + schema;
        }
        if (RESERVED.contains(schema)) {
            throw new IllegalArgumentException("Tenant identifier maps to a reserved schema: " + tenantId);
        }
        return new TenantRef(id, schema);
    }
}
