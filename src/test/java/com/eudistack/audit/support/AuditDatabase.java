package com.eudistack.audit.support;

import com.zaxxer.hikari.HikariDataSource;
import com.eudistack.audit.application.workflow.ProvisionTenantWorkflow;
import com.eudistack.audit.infrastructure.archive.SchemaMigrator;
import com.eudistack.audit.infrastructure.config.AuditProperties;
import com.eudistack.audit.infrastructure.persistence.repository.TenantDirectoryJdbc;
import com.eudistack.audit.infrastructure.persistence.tenant.AuditDataSourceConfiguration;
import com.eudistack.audit.infrastructure.persistence.tenant.JdbcPool;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

/**
 * One PostgreSQL 17 for every integration test, with the four roles of AD-10, provisioned the way the
 * {@code provisioner} task does it: {@code platform} plus the test tenants, as {@code audit_migrator}.
 */
public final class AuditDatabase {

    public static final List<String> TENANTS = List.of("red-logistica", "sandbox");

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("audit")
            .withInitScript("db/test-roles.sql");

    private static volatile boolean provisioned;

    private AuditDatabase() {
    }

    public static synchronized void start() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        if (!provisioned) {
            try (HikariDataSource migrator = create("migrator", "audit_migrator", "migrator")) {
                JdbcPool pool = new JdbcPool(migrator);
                ProvisionTenantWorkflow workflow = new ProvisionTenantWorkflow(
                        new SchemaMigrator(migrator, new AuditProperties.DbRoles()), new TenantDirectoryJdbc(pool));
                TENANTS.forEach(workflow::provision);
            }
            provisioned = true;
        }
    }

    public static String jdbcUrl() {
        start();
        return POSTGRES.getJdbcUrl();
    }

    public static void register(DynamicPropertyRegistry registry) {
        start();
        registry.add("audit.datasource.writer.url", POSTGRES::getJdbcUrl);
        registry.add("audit.datasource.writer.username", () -> "audit_writer");
        registry.add("audit.datasource.writer.password", () -> "writer");
        registry.add("audit.datasource.reader.url", POSTGRES::getJdbcUrl);
        registry.add("audit.datasource.reader.username", () -> "audit_reader");
        registry.add("audit.datasource.reader.password", () -> "reader");
        registry.add("audit.datasource.archiver.url", POSTGRES::getJdbcUrl);
        registry.add("audit.datasource.archiver.username", () -> "audit_archiver");
        registry.add("audit.datasource.archiver.password", () -> "archiver");
    }

    /**
     * A pool as one of the roles ({@code audit_writer}, …) or as the container superuser ({@code null}).
     */
    public static HikariDataSource pool(String name, String user, String password) {
        start();
        return create(name, user, password);
    }

    private static HikariDataSource create(String name, String user, String password) {
        AuditProperties.Pool pool = new AuditProperties.Pool();
        pool.setUrl(POSTGRES.getJdbcUrl());
        pool.setUsername(user == null ? POSTGRES.getUsername() : user);
        pool.setPassword(user == null ? POSTGRES.getPassword() : password);
        pool.setPoolName("test-" + name);
        pool.setMaximumPoolSize(4);
        return AuditDataSourceConfiguration.dataSource(pool);
    }

    /**
     * JDBC as the container superuser, to inspect rows or plant old ones.
     */
    public static JdbcClient superuser() {
        return SuperuserHolder.JDBC;
    }

    private static final class SuperuserHolder {
        private static final JdbcClient JDBC = JdbcClient.create(pool("superuser", null, null));
    }
}
