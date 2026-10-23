package com.eudistack.audit.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.eudistack.audit.application.workflow.ArchiveEventsWorkflow;
import com.eudistack.audit.application.workflow.RecoverArchiveWorkflow;
import com.eudistack.audit.domain.policy.SelfEventFactory;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;
import com.eudistack.audit.domain.port.out.ColdStoragePort;
import com.eudistack.audit.infrastructure.archive.ArchiveOnceRunner;
import com.eudistack.audit.infrastructure.archive.ArchiveWorker;
import com.eudistack.audit.infrastructure.archive.RecoverArchiveRunner;
import com.eudistack.audit.infrastructure.persistence.repository.ArchiveJdbc;
import com.eudistack.audit.infrastructure.persistence.repository.EventSql;
import com.eudistack.audit.infrastructure.persistence.repository.SelfAuditJdbc;
import com.eudistack.audit.infrastructure.persistence.repository.TenantDirectoryJdbc;
import com.eudistack.audit.infrastructure.persistence.tenant.AuditDataSourceConfiguration;
import com.eudistack.audit.infrastructure.persistence.tenant.JdbcPool;
import com.eudistack.audit.infrastructure.security.CallerClaims;
import com.eudistack.audit.infrastructure.storage.ColdStorageS3;
import com.eudistack.audit.infrastructure.storage.FileSystemColdStorage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;

/**
 * Archiving and recovery ({@code archiver} and {@code all}). No business HTTP.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAuditRole({AuditRole.ARCHIVER, AuditRole.ALL})
public class ArchiverConfiguration {

    @Bean
    public ColdStoragePort coldStorage(AuditProperties properties) {
        AuditProperties.Archive archive = properties.getArchive();
        if ("filesystem".equalsIgnoreCase(archive.getStorage())) {
            return new FileSystemColdStorage(Path.of(archive.getFilesystemRoot()));
        }
        if (archive.getBucket() == null || archive.getBucket().isBlank()) {
            throw new IllegalStateException("audit.archive.bucket is required with the s3 cold storage");
        }
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(archive.getRegion()))
                .forcePathStyle(archive.isPathStyle());
        if (archive.getEndpoint() != null && !archive.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(archive.getEndpoint()));
        }
        return new ColdStorageS3(builder.build(), archive.getBucket());
    }

    @Bean
    public ArchiveJdbc archiveJdbc(@Qualifier(AuditDataSourceConfiguration.ARCHIVER) JdbcPool pool, EventSql sql) {
        return new ArchiveJdbc(pool, sql);
    }

    @Bean
    public ArchiveEventsWorkflow archiveEventsWorkflow(TenantDirectoryJdbc directory, ArchiveJdbc repository,
                                                       ColdStoragePort storage, SelfEventFactory selfEvents,
                                                       AuditTelemetryPort telemetry, Clock clock) {
        return new ArchiveEventsWorkflow(directory, repository, storage, selfEvents, telemetry, clock);
    }

    @Bean
    public RecoverArchiveWorkflow recoverArchiveWorkflow(TenantDirectoryJdbc directory, ColdStoragePort storage,
                                                         ArchiveEventsWorkflow archive,
                                                         @Qualifier(AuditDataSourceConfiguration.ARCHIVER)
                                                         JdbcPool pool, EventSql sql, SelfEventFactory selfEvents) {
        return new RecoverArchiveWorkflow(directory, storage, archive, new SelfAuditJdbc(pool, sql), selfEvents);
    }

    @Bean
    public ArchiveWorker archiveWorker(ArchiveEventsWorkflow workflow) {
        return new ArchiveWorker(workflow);
    }

    @Bean
    @ConditionalOnProperty(name = "audit.archive.run-once", havingValue = "true")
    public ArchiveOnceRunner archiveOnceRunner(ArchiveWorker worker) {
        return new ArchiveOnceRunner(worker);
    }

    @Bean
    @ConditionalOnProperty(name = "audit.recover.enabled", havingValue = "true")
    public RecoverArchiveRunner recoverArchiveRunner(RecoverArchiveWorkflow workflow, AuditProperties properties,
                                                     CallerClaims claims, ObjectMapper mapper) {
        return new RecoverArchiveRunner(workflow, CoreConfiguration.nimbusVerifier(properties.getSecurity(), claims),
                properties.getRecover(), mapper);
    }
}
