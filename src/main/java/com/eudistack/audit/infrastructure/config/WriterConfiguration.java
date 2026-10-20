package com.eudistack.audit.infrastructure.config;

import com.eudistack.audit.application.workflow.RecordEventsWorkflow;
import com.eudistack.audit.domain.catalogue.CatalogueValidator;
import com.eudistack.audit.domain.policy.SelfEventFactory;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;
import com.eudistack.audit.infrastructure.persistence.repository.EventSql;
import com.eudistack.audit.infrastructure.persistence.repository.JdbcEventStore;
import com.eudistack.audit.infrastructure.persistence.repository.SelfAuditJdbc;
import com.eudistack.audit.infrastructure.persistence.repository.TenantDirectoryJdbc;
import com.eudistack.audit.infrastructure.persistence.tenant.AuditDataSourceConfiguration;
import com.eudistack.audit.infrastructure.persistence.tenant.JdbcPool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Write face ({@code writer} and {@code all}): JDBC only, no Hibernate (AD-1, AD-10).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAuditRole({AuditRole.WRITER, AuditRole.ALL})
public class WriterConfiguration {

    @Bean
    public RecordEventsWorkflow recordEventsWorkflow(CatalogueValidator validator, TenantDirectoryJdbc directory,
                                                     @Qualifier(AuditDataSourceConfiguration.WRITER) JdbcPool pool,
                                                     EventSql sql, SelfEventFactory selfEvents,
                                                     AuditTelemetryPort telemetry) {
        return new RecordEventsWorkflow(validator, directory, new JdbcEventStore(pool, sql),
                new SelfAuditJdbc(pool, sql), selfEvents, telemetry);
    }
}
