package com.eudistack.audit.infrastructure.archive;

import com.eudistack.audit.domain.model.TenantRef;
import com.eudistack.audit.domain.port.out.SchemaMigratorPort;
import com.eudistack.audit.infrastructure.config.AuditProperties;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Flyway, run by the {@code provisioner} profile as {@code audit_migrator} (AD-3, ADR-040): the DDL and
 * the grants never run under a runtime role.
 *
 * <ul>
 *   <li>{@code db/migration/platform} → schema {@code platform}.</li>
 *   <li>{@code db/migration/tenant} → one schema per tenant, same template, own history table.</li>
 * </ul>
 */
public final class SchemaMigrator implements SchemaMigratorPort {

    private final DataSource dataSource;
    private final Map<String, String> placeholders;

    public SchemaMigrator(DataSource dataSource, AuditProperties.DbRoles roles) {
        this.dataSource = dataSource;
        this.placeholders = Map.of(
                "writer_role", roles.getWriter(),
                "reader_role", roles.getReader(),
                "archiver_role", roles.getArchiver());
    }

    @Override
    public void migratePlatform() {
        migrate(TenantRef.PLATFORM.schemaName(), "classpath:db/migration/platform");
    }

    @Override
    public void migrateTenant(String schemaName) {
        TenantRef.requireValidSchemaName(schemaName);
        if (TenantRef.PLATFORM.schemaName().equals(schemaName)) {
            throw new IllegalArgumentException("platform is not a tenant schema");
        }
        migrate(schemaName, "classpath:db/migration/tenant");
    }

    private void migrate(String schema, String location) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations(location)
                .placeholders(placeholders)
                .validateMigrationNaming(true)
                .load()
                .migrate();
    }
}
