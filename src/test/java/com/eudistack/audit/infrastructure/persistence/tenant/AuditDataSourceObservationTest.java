package com.eudistack.audit.infrastructure.persistence.tenant;

import com.zaxxer.hikari.HikariDataSource;
import com.eudistack.audit.AuditApplication;
import com.eudistack.audit.infrastructure.observability.DatabaseWritesHandler;
import com.eudistack.audit.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four pools are built by hand, so Boot's single {@code spring.datasource} proxy does not cover
 * them. datasource-micrometer replaces each {@code DataSource} bean; the pool must keep using that
 * proxy, and the Hikari target must stay reachable for its metrics and for {@code close}.
 */
class AuditDataSourceObservationTest {

    @Test
    void writer_UsesTheObservationProxyAndKeepsTheHikariTarget() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(AuditApplication.class).run(
                "--audit.role=writer",
                "--server.port=0",
                "--management.server.port=0",
                "--audit.scheduling.enabled=false",
                "--management.otlp.metrics.export.enabled=false",
                "--management.otlp.tracing.export.enabled=false",
                "--management.otlp.logging.export.enabled=false",
                "--audit.security.jwks=" + TestTokens.publicJwks(),
                "--audit.security.trusted-issuers=" + TestTokens.ISSUER_TEMPLATE,
                "--audit.security.audience=https://audit.internal.test",
                "--audit.query.cursor-secret=observation-test-cursor-secret-012345",
                "--audit.archive.storage=filesystem",
                "--audit.archive.filesystem-root=build/observation-cold-storage",
                "--audit.datasource.writer.url=jdbc:postgresql://127.0.0.1:1/audit",
                "--audit.datasource.writer.username=audit_writer",
                "--audit.datasource.writer.password=writer")) {
            DataSource dataSource = context.getBean("writerDataSource", DataSource.class);
            assertThat(dataSource).isNotInstanceOf(HikariDataSource.class);

            Object target = AopProxyUtils.getSingletonTarget(dataSource);
            assertThat(target).isInstanceOf(HikariDataSource.class);
            HikariDataSource hikari = (HikariDataSource) target;
            assertThat(hikari.getDataSourceProperties().getProperty("logServerErrorDetail")).isEqualTo("false");
            assertThat(hikari.isClosed()).isFalse();

            JdbcPool pool = context.getBean(AuditDataSourceConfiguration.WRITER, JdbcPool.class);
            assertThat(pool.dataSource()).isSameAs(dataSource);
            assertThat(context.getBean(DatabaseWritesHandler.class)).isNotNull();
            assertThat(context.getEnvironment().getProperty("jdbc.includes")).isEqualTo("QUERY");
            assertThat(context.getEnvironment().getProperty("jdbc.opentelemetry.metrics.enabled")).isEqualTo("false");
            assertThat(context.getEnvironment().getProperty("management.metrics.enable.all")).isEqualTo("false");
        }
    }
}
