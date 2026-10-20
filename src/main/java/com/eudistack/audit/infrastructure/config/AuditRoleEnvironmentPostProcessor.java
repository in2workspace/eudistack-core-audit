package com.eudistack.audit.infrastructure.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One-shot tasks (provisioning, a single archiving run, a recovery) do not open any HTTP port, and
 * {@code service.name} follows the role ({@code audit-writer}, {@code audit-reader}, … — design §3.10).
 */
public class AuditRoleEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String role = environment.getProperty("audit.role", "all").trim().toLowerCase(Locale.ROOT);
        Map<String, Object> properties = new HashMap<>();
        boolean oneShot = "provisioner".equals(role)
                || environment.getProperty("audit.archive.run-once", Boolean.class, false)
                || environment.getProperty("audit.recover.enabled", Boolean.class, false);
        if (oneShot) {
            properties.put("spring.main.web-application-type", "none");
            properties.put("audit.scheduling.enabled", "false");
        }
        if (environment.getProperty("spring.application.name") == null) {
            properties.put("spring.application.name", "audit-" + role);
        }
        if (!properties.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("auditRole", properties));
        }
    }
}
