package com.eudistack.audit.infrastructure.archive;

import com.eudistack.audit.application.workflow.ProvisionTenantWorkflow;
import com.eudistack.audit.domain.model.TenantRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;

import java.util.List;

/**
 * {@code audit.role=provisioner}: migrates {@code platform} and provisions the tenants of
 * {@code audit.provisioner.tenants}, then exits (AD-3). The platform onboarding runs it for each new
 * tenant; locally, the compose stack runs it before the API.
 */
public final class ProvisionerRunner implements OneShotTask {

    private static final Logger LOG = LoggerFactory.getLogger(ProvisionerRunner.class);

    private final ProvisionTenantWorkflow workflow;
    private final List<String> tenants;
    private int exitCode;

    public ProvisionerRunner(ProvisionTenantWorkflow workflow, List<String> tenants) {
        this.workflow = workflow;
        this.tenants = List.copyOf(tenants);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            workflow.provisionPlatform();
            LOG.info("Schema platform is up to date");
            for (String tenant : tenants) {
                TenantRef ref = workflow.provision(tenant);
                LOG.info("Tenant {} provisioned in schema {}", ref.tenantId(), ref.schemaName());
            }
        } catch (RuntimeException e) {
            LOG.error("Provisioning failed", e);
            exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
