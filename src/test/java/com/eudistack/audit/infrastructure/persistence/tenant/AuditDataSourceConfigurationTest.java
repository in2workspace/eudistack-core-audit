package com.eudistack.audit.infrastructure.persistence.tenant;

import com.zaxxer.hikari.HikariDataSource;
import com.eudistack.audit.infrastructure.config.AuditProperties;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class AuditDataSourceConfigurationTest {

    @Test
    void dataSource_HidesPostgreSqlServerErrorDetail() {
        try (HikariDataSource dataSource = AuditDataSourceConfiguration.dataSource(pool())) {
            assertThat(dataSource.getDataSourceProperties().getProperty("logServerErrorDetail")).isEqualTo("false");
            assertThat(dataSource.getDataSourceProperties().getProperty("password")).isNull();
        }
    }

    @Test
    void close_ClosesTheHikariPoolUnderTheDataSourceProxy() {
        HikariDataSource raw = AuditDataSourceConfiguration.dataSource(pool());
        ProxyFactory factory = new ProxyFactory();
        factory.setTarget(raw);
        factory.setInterfaces(DataSource.class);
        DataSource proxy = (DataSource) factory.getProxy();

        JdbcPool.close(proxy);

        assertThat(raw.isClosed()).isTrue();
    }

    private static AuditProperties.Pool pool() {
        AuditProperties.Pool pool = new AuditProperties.Pool();
        pool.setUrl("jdbc:postgresql://localhost:5432/audit");
        pool.setUsername("audit_writer");
        pool.setPassword("secret");
        pool.setPoolName("audit-writer");
        pool.setMaximumPoolSize(1);
        return pool;
    }
}
