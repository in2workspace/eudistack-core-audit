package com.eudistack.audit.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.eudistack.audit.application.workflow.GetEventWorkflow;
import com.eudistack.audit.application.workflow.ListEventsWorkflow;
import com.eudistack.audit.application.workflow.QueryAccessGuard;
import com.eudistack.audit.application.workflow.QueryRateLimiter;
import com.eudistack.audit.domain.policy.CursorCodec;
import com.eudistack.audit.domain.policy.SelfEventFactory;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;
import com.eudistack.audit.domain.port.out.CatalogueResolverPort;
import com.eudistack.audit.infrastructure.persistence.repository.EventSql;
import com.eudistack.audit.infrastructure.persistence.repository.JdbcEventQuery;
import com.eudistack.audit.infrastructure.persistence.repository.RateLimiterJdbc;
import com.eudistack.audit.infrastructure.persistence.repository.SelfAuditJdbc;
import com.eudistack.audit.infrastructure.persistence.repository.TenantDirectoryJdbc;
import com.eudistack.audit.infrastructure.persistence.tenant.AuditDataSourceConfiguration;
import com.eudistack.audit.infrastructure.persistence.tenant.JdbcPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Query face ({@code reader} and {@code all}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAuditRole({AuditRole.READER, AuditRole.ALL})
public class ReaderConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(ReaderConfiguration.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    @Bean
    public QueryAccessGuard queryAccessGuard(TenantDirectoryJdbc directory,
                                             @Qualifier(AuditDataSourceConfiguration.READER) JdbcPool pool,
                                             EventSql sql, SelfEventFactory selfEvents) {
        return new QueryAccessGuard(directory, new SelfAuditJdbc(pool, sql), selfEvents);
    }

    @Bean
    public JdbcEventQuery eventQuery(@Qualifier(AuditDataSourceConfiguration.READER) JdbcPool pool, EventSql sql,
                                     TenantDirectoryJdbc directory, ObjectMapper mapper) {
        return new JdbcEventQuery(pool, sql, directory, mapper);
    }

    @Bean
    public CursorCodec cursorCodec(AuditProperties properties) {
        String secret = properties.getQuery().getCursorSecret();
        if (secret == null || secret.isBlank()) {
            if (properties.getRole() == AuditRole.READER) {
                throw new IllegalStateException("audit.query.cursor-secret is required: every reader replica "
                        + "must sign cursors with the same key");
            }
            LOG.warn("audit.query.cursor-secret not set: using a random key, cursors only work on this process");
            byte[] random = new byte[32];
            RANDOM.nextBytes(random);
            return new CursorCodec(random);
        }
        return new CursorCodec(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Bean
    public ListEventsWorkflow listEventsWorkflow(QueryAccessGuard guard, JdbcEventQuery queries,
                                                 CatalogueResolverPort catalogue, CursorCodec cursors,
                                                 AuditTelemetryPort telemetry, Clock clock) {
        return new ListEventsWorkflow(guard, queries, catalogue, cursors, telemetry, clock);
    }

    @Bean
    public GetEventWorkflow getEventWorkflow(QueryAccessGuard guard, JdbcEventQuery queries,
                                             AuditTelemetryPort telemetry, Clock clock) {
        return new GetEventWorkflow(guard, queries, telemetry, clock);
    }

    @Bean
    public RateLimiterJdbc rateLimiterJdbc(@Qualifier(AuditDataSourceConfiguration.READER) JdbcPool pool) {
        return new RateLimiterJdbc(pool);
    }

    @Bean
    public QueryRateLimiter queryRateLimiter(RateLimiterJdbc counters, Clock clock, AuditProperties properties,
                                             AuditTelemetryPort telemetry) {
        Map<String, QueryRateLimiter.Limits> overrides = new HashMap<>();
        properties.getQuery().getRateLimitOverrides().forEach((org, limits) -> overrides.put(org, limits(limits)));
        return new QueryRateLimiter(counters, clock, limits(properties.getQuery().getRateLimit()), overrides,
                telemetry);
    }

    @Bean
    public RateWindowJanitor rateWindowJanitor(RateLimiterJdbc counters, Clock clock) {
        return new RateWindowJanitor(counters, clock);
    }

    private static QueryRateLimiter.Limits limits(AuditProperties.RateLimits limits) {
        return new QueryRateLimiter.Limits(limits.getSystem(), limits.getPerson(), limits.getSystemSearch(),
                limits.getPersonSearch());
    }

    /**
     * Removes the expired windows of the rate limiter once a minute.
     */
    public static class RateWindowJanitor {

        private final RateLimiterJdbc counters;
        private final Clock clock;

        public RateWindowJanitor(RateLimiterJdbc counters, Clock clock) {
            this.counters = counters;
            this.clock = clock;
        }

        @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
        public void purge() {
            try {
                counters.purgeBefore(clock.instant().minus(Duration.ofMinutes(5)));
            } catch (RuntimeException e) {
                LOG.warn("Expired rate windows could not be purged: {}", e.getMessage());
            }
        }
    }
}
