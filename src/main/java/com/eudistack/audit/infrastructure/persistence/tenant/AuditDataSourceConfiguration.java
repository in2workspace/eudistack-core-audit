package com.eudistack.audit.infrastructure.persistence.tenant;

import com.zaxxer.hikari.HikariDataSource;
import com.eudistack.audit.infrastructure.config.AuditProperties;
import com.eudistack.audit.infrastructure.config.AuditRole;
import com.eudistack.audit.infrastructure.config.ConditionalOnAuditRole;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * One pool per profile, never one per tenant (AD-2): each process opens only the pool of its role
 * (design §3.4.2). The {@code all} role opens the three runtime pools, each with its own database role.
 */
@Configuration(proxyBeanMethods = false)
public class AuditDataSourceConfiguration {

    public static final String WRITER = "writerPool";
    public static final String READER = "readerPool";
    public static final String ARCHIVER = "archiverPool";
    public static final String MIGRATOR = "migratorPool";

    // destroyMethod is empty: datasource-micrometer replaces the bean with a DataSource proxy that
    // has no close(). JdbcPool destroys the Hikari pool underneath.
    @Bean(destroyMethod = "")
    @ConditionalOnAuditRole({AuditRole.WRITER, AuditRole.ALL})
    public HikariDataSource writerDataSource(AuditProperties properties) {
        return dataSource(properties.getDatasource().getWriter());
    }

    @Bean(destroyMethod = "")
    @ConditionalOnAuditRole({AuditRole.READER, AuditRole.ALL})
    public HikariDataSource readerDataSource(AuditProperties properties) {
        return dataSource(properties.getDatasource().getReader());
    }

    @Bean(destroyMethod = "")
    @ConditionalOnAuditRole({AuditRole.ARCHIVER, AuditRole.ALL})
    public HikariDataSource archiverDataSource(AuditProperties properties) {
        return dataSource(properties.getDatasource().getArchiver());
    }

    @Bean(destroyMethod = "")
    @ConditionalOnAuditRole(AuditRole.PROVISIONER)
    public HikariDataSource migratorDataSource(AuditProperties properties) {
        return dataSource(properties.getDatasource().getMigrator());
    }

    @Bean(WRITER)
    @ConditionalOnAuditRole({AuditRole.WRITER, AuditRole.ALL})
    public JdbcPool writerPool(@Qualifier("writerDataSource") DataSource dataSource) {
        return new JdbcPool(dataSource);
    }

    @Bean(READER)
    @ConditionalOnAuditRole({AuditRole.READER, AuditRole.ALL})
    public JdbcPool readerPool(@Qualifier("readerDataSource") DataSource dataSource) {
        return new JdbcPool(dataSource);
    }

    @Bean(ARCHIVER)
    @ConditionalOnAuditRole({AuditRole.ARCHIVER, AuditRole.ALL})
    public JdbcPool archiverPool(@Qualifier("archiverDataSource") DataSource dataSource) {
        return new JdbcPool(dataSource);
    }

    @Bean(MIGRATOR)
    @ConditionalOnAuditRole(AuditRole.PROVISIONER)
    public JdbcPool migratorPool(@Qualifier("migratorDataSource") DataSource dataSource) {
        return new JdbcPool(dataSource);
    }

    /**
     * Hikari parameters of design §3.4.2. The pool is created lazily (no-arg constructor) so that Spring
     * Boot can bind its Micrometer tracker ({@code hikaricp.*}, label {@code pool}) before it starts.
     */
    public static HikariDataSource dataSource(AuditProperties.Pool pool) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(pool.getUrl());
        dataSource.setUsername(pool.getUsername());
        dataSource.setPassword(pool.getPassword());
        dataSource.setPoolName(pool.getPoolName());
        dataSource.setMaximumPoolSize(pool.getMaximumPoolSize());
        dataSource.setMinimumIdle(Math.min(2, pool.getMaximumPoolSize()));
        dataSource.setConnectionTimeout(pool.getConnectionTimeout().toMillis());
        dataSource.setValidationTimeout(1000);
        dataSource.setIdleTimeout(600_000);
        dataSource.setMaxLifetime(1_800_000);
        dataSource.setKeepaliveTime(300_000);
        dataSource.setLeakDetectionThreshold(10_000);
        // Uniqueness is closed by the primary key, not by the isolation level.
        dataSource.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        dataSource.setRegisterMbeans(true);
        // PostgreSQL's error Detail repeats the row or the key. Without it, a failed statement
        // keeps the constraint and the SQLState (OBS-05, OBS-10).
        dataSource.addDataSourceProperty("logServerErrorDetail", "false");
        // No connectionInitSql: the schema is never fixed when the connection is created.
        return dataSource;
    }
}
