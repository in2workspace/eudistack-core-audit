package com.eudistack.audit.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import com.eudistack.audit.domain.catalogue.CatalogueValidator;
import com.eudistack.audit.domain.policy.SelfEventFactory;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;
import com.eudistack.audit.domain.port.out.CatalogueResolverPort;
import com.eudistack.audit.infrastructure.catalogue.ClasspathCatalogue;
import com.eudistack.audit.infrastructure.observability.MicrometerAuditTelemetry;
import com.eudistack.audit.infrastructure.observability.OpenTelemetryLogsInitializer;
import com.eudistack.audit.infrastructure.persistence.repository.EventSql;
import com.eudistack.audit.infrastructure.persistence.repository.TenantDirectoryJdbc;
import com.eudistack.audit.infrastructure.persistence.tenant.AuditDataSourceConfiguration;
import com.eudistack.audit.infrastructure.persistence.tenant.JdbcPool;
import com.eudistack.audit.infrastructure.security.CallerClaims;
import com.eudistack.audit.infrastructure.security.NimbusTokenVerifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.time.Clock;
import java.util.stream.Stream;

/**
 * Beans every runtime role shares: clock, catalogue, events of the API itself, telemetry, tenant
 * directory and token verification.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuditProperties.class)
public class CoreConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public CatalogueResolverPort catalogue(AuditProperties properties) {
        return new ClasspathCatalogue(properties.getCatalogue().getLocations());
    }

    @Bean
    public CatalogueValidator catalogueValidator(CatalogueResolverPort catalogue) {
        return new CatalogueValidator(catalogue);
    }

    @Bean
    public SelfEventFactory selfEventFactory(Clock clock, CatalogueResolverPort catalogue) {
        return new SelfEventFactory(clock, catalogue.latest().version());
    }

    @Bean
    public AuditTelemetryPort auditTelemetry(MeterRegistry meters, ObjectProvider<ObservationRegistry> observations) {
        return new MicrometerAuditTelemetry(meters, observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    /**
     * Log records over OTLP (design §3.10), when {@code management.otlp.logging.export.enabled} is on.
     */
    @Bean
    public OpenTelemetryLogsInitializer openTelemetryLogsInitializer(ObjectProvider<OpenTelemetry> openTelemetry) {
        return new OpenTelemetryLogsInitializer(openTelemetry.getIfAvailable(OpenTelemetry::noop));
    }

    @Bean
    public EventSql eventSql(ObjectMapper mapper) {
        return new EventSql(mapper);
    }

    /**
     * Read with the pool of the role; every runtime role has {@code SELECT} on {@code tenant_directory}.
     */
    @Bean
    @ConditionalOnAuditRole({AuditRole.WRITER, AuditRole.READER, AuditRole.ARCHIVER, AuditRole.ALL})
    public TenantDirectoryJdbc tenantDirectory(
            @Qualifier(AuditDataSourceConfiguration.WRITER) ObjectProvider<JdbcPool> writer,
            @Qualifier(AuditDataSourceConfiguration.READER) ObjectProvider<JdbcPool> reader,
            @Qualifier(AuditDataSourceConfiguration.ARCHIVER) ObjectProvider<JdbcPool> archiver) {
        JdbcPool pool = Stream.of(reader, writer, archiver)
                .map(ObjectProvider::getIfAvailable)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow();
        return new TenantDirectoryJdbc(pool);
    }

    @Bean
    public CallerClaims callerClaims(AuditProperties properties) {
        return new CallerClaims(properties.getWeb().getEnvironmentSuffixes());
    }

    @Bean
    @ConditionalOnAuditRole({AuditRole.WRITER, AuditRole.READER, AuditRole.ALL})
    public NimbusTokenVerifier tokenVerifier(AuditProperties properties, CallerClaims claims) {
        return tokenVerifier(properties.getSecurity(), claims);
    }

    /**
     * @param jwks inline JWKS ({@code audit.security.jwks}, tests and local development) or, when absent,
     *             the remote JWKS of the Verifier ({@code audit.security.jwks-uri})
     */
    public static NimbusTokenVerifier tokenVerifier(AuditProperties.Security security, CallerClaims claims) {
        return new NimbusTokenVerifier(jwkSource(security), security, claims);
    }

    static JWKSource<SecurityContext> jwkSource(AuditProperties.Security security) {
        if (security.getJwks() != null && !security.getJwks().isBlank()) {
            try {
                return new ImmutableJWKSet<>(JWKSet.parse(security.getJwks()));
            } catch (ParseException e) {
                throw new IllegalStateException("audit.security.jwks is not a valid JWKS", e);
            }
        }
        if (security.getJwksUri() == null || security.getJwksUri().isBlank()) {
            throw new IllegalStateException("audit.security.jwks-uri (Verifier JWKS) is required");
        }
        int timeout = (int) security.getJwksTimeout().toMillis();
        try {
            return JWKSourceBuilder.create(URI.create(security.getJwksUri()).toURL(),
                            new DefaultResourceRetriever(timeout, timeout, 512 * 1024))
                    .build();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("audit.security.jwks-uri is not a URL", e);
        }
    }
}
