package com.eudistack.audit;

import com.eudistack.audit.application.workflow.ArchiveEventsWorkflow;
import com.eudistack.audit.application.workflow.ProvisionTenantWorkflow;
import com.eudistack.audit.application.workflow.RecordEventsWorkflow;
import com.eudistack.audit.domain.port.in.ListEventsPort;
import com.eudistack.audit.support.AuditDatabase;
import com.eudistack.audit.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each deployable role starts with only its own beans and pool (design §3.2, AD-10): the image is one,
 * what runs is {@code audit.role}.
 */
class RoleContextIT {

    private static ConfigurableApplicationContext start(String role, String... extra) {
        AuditDatabase.start();
        List<String> args = new ArrayList<>(List.of(
                "--audit.role=" + role,
                "--server.port=0",
                "--management.server.port=0",
                "--audit.scheduling.enabled=false",
                "--management.otlp.metrics.export.enabled=false",
                "--audit.security.jwks=" + TestTokens.publicJwks(),
                "--audit.security.trusted-issuers=" + TestTokens.ISSUER_TEMPLATE,
                "--audit.security.audience=https://audit.internal.test",
                "--audit.query.cursor-secret=role-context-test-cursor-secret-012345",
                "--audit.archive.storage=filesystem",
                "--audit.archive.filesystem-root=build/role-cold-storage"));
        String url = AuditDatabase.jdbcUrl();
        for (String pool : List.of("writer", "reader", "archiver", "migrator")) {
            String user = "audit_" + pool;
            args.add("--audit.datasource." + pool + ".url=" + url);
            args.add("--audit.datasource." + pool + ".username=" + user);
            args.add("--audit.datasource." + pool + ".password=" + pool);
        }
        args.addAll(List.of(extra));
        return new SpringApplicationBuilder(AuditApplication.class).run(args.toArray(String[]::new));
    }

    @Test
    void writer_onlyWrites() {
        try (ConfigurableApplicationContext context = start("writer")) {
            assertThat(context.getBeansOfType(RecordEventsWorkflow.class)).hasSize(1);
            assertThat(context.getBeansOfType(ListEventsPort.class)).isEmpty();
            assertThat(context.getBeansOfType(ArchiveEventsWorkflow.class)).isEmpty();
            assertThat(context.containsBean("readerDataSource")).isFalse();
        }
    }

    @Test
    void reader_onlyReads() {
        try (ConfigurableApplicationContext context = start("reader")) {
            assertThat(context.getBeansOfType(ListEventsPort.class)).hasSize(1);
            assertThat(context.getBeansOfType(RecordEventsWorkflow.class)).isEmpty();
            assertThat(context.containsBean("writerDataSource")).isFalse();
        }
    }

    @Test
    void archiver_hasNoBusinessHttp() {
        try (ConfigurableApplicationContext context = start("archiver")) {
            assertThat(context.getBeansOfType(ArchiveEventsWorkflow.class)).hasSize(1);
            assertThat(context.getBeansOfType(RecordEventsWorkflow.class)).isEmpty();
            assertThat(context.getBeansOfType(ListEventsPort.class)).isEmpty();
        }
    }

    @Test
    void provisioner_runsWithoutHttp_andIsIdempotent() {
        try (ConfigurableApplicationContext context = start("provisioner",
                "--audit.provisioner.tenants=sandbox,red-logistica")) {
            assertThat(context.getBeansOfType(ProvisionTenantWorkflow.class)).hasSize(1);
            assertThat(context.containsBean("writerDataSource")).isFalse();
            assertThat(context.getEnvironment().getProperty("spring.main.web-application-type")).isEqualTo("none");
        }
    }
}
