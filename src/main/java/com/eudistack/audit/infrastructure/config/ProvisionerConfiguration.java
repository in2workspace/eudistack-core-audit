package com.eudistack.audit.infrastructure.config;

import com.eudistack.audit.application.workflow.ProvisionTenantWorkflow;
import com.eudistack.audit.infrastructure.archive.ProvisionerRunner;
import com.eudistack.audit.infrastructure.archive.SchemaMigrator;
import com.eudistack.audit.infrastructure.persistence.repository.TenantDirectoryJdbc;
import com.eudistack.audit.infrastructure.persistence.tenant.AuditDataSourceConfiguration;
import com.eudistack.audit.infrastructure.persistence.tenant.JdbcPool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One-shot provisioning ({@code provisioner}), as {@code audit_migrator} (AD-3).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAuditRole(AuditRole.PROVISIONER)
public class ProvisionerConfiguration {

    @Bean
    public ProvisionTenantWorkflow provisionTenantWorkflow(
            @Qualifier(AuditDataSourceConfiguration.MIGRATOR) JdbcPool pool, AuditProperties properties) {
        return new ProvisionTenantWorkflow(new SchemaMigrator(pool.dataSource(), properties.getDbRoles()),
                new TenantDirectoryJdbc(pool));
    }

    @Bean
    public ProvisionerRunner provisionerRunner(ProvisionTenantWorkflow workflow, AuditProperties properties) {
        return new ProvisionerRunner(workflow, properties.getProvisioner().getTenants());
    }
}
