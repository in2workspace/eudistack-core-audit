package com.eudistack.audit;

import com.eudistack.audit.infrastructure.archive.OneShotTask;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Audit API service ({@code eudistack-core-audit}). One binary; what it runs is decided by
 * {@code audit.role} (writer, reader, archiver, all, provisioner).
 *
 * <p>The data sources are built per role ({@code AuditDataSourceConfiguration}), never by Spring Boot,
 * and Flyway only runs in the provisioner, never at start-up of a runtime role.
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        JdbcClientAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        SqlInitializationAutoConfiguration.class})
public class AuditApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(AuditApplication.class, args);
        if (context.getBeanProvider(OneShotTask.class).stream().findAny().isPresent()) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
