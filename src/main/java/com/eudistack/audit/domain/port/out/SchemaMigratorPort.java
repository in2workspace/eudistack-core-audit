package com.eudistack.audit.domain.port.out;

/**
 * Applies the schema migrations ({@code audit_migrator}, AD-3).
 */
public interface SchemaMigratorPort {

    void migratePlatform();

    void migrateTenant(String schemaName);
}
