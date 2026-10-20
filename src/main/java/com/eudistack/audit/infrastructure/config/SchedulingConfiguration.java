package com.eudistack.audit.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduled jobs: the rate window janitor (reader) and the daily archiving (archiver). Off in one-shot
 * tasks and when {@code audit.scheduling.enabled=false} (tests).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnAuditRole({AuditRole.READER, AuditRole.ARCHIVER, AuditRole.ALL})
@ConditionalOnProperty(name = "audit.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfiguration {
}
