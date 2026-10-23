package com.eudistack.audit.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import com.eudistack.audit.application.workflow.QueryRateLimiter;
import com.eudistack.audit.domain.port.in.RecordEventsPort;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;
import com.eudistack.audit.infrastructure.security.NimbusTokenVerifier;
import com.eudistack.audit.infrastructure.web.BearerAuthenticationFilter;
import com.eudistack.audit.infrastructure.web.HostPolicyFilter;
import com.eudistack.audit.infrastructure.web.NoStoreFilter;
import com.eudistack.audit.infrastructure.web.ProblemMapper;
import com.eudistack.audit.infrastructure.web.RateLimitFilter;
import com.eudistack.audit.infrastructure.web.TenantResolutionFilter;
import com.eudistack.audit.infrastructure.web.TraceIds;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import jakarta.servlet.Filter;

/**
 * HTTP filters of design §3.4.1, in order: no-store, host policy (405 / Origin 403), bearer
 * authentication, tenant resolution and rate limit (read face only). They only apply to the API paths.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAuditRole({AuditRole.WRITER, AuditRole.READER, AuditRole.ALL})
public class WebConfiguration {

    private static final String[] API_PATTERNS = {"/api/*", "/audit/api/*"};
    private static final int BASE_ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    @Bean
    public ProblemMapper problemMapper(ObjectMapper mapper) {
        return new ProblemMapper(mapper);
    }

    @Bean
    public TraceIds traceIds(ObjectProvider<Tracer> tracer) {
        return new TraceIds(tracer);
    }

    @Bean
    public FilterRegistrationBean<NoStoreFilter> noStoreFilter() {
        return register(new NoStoreFilter(), 0);
    }

    @Bean
    public FilterRegistrationBean<HostPolicyFilter> hostPolicyFilter(AuditProperties properties, ProblemMapper problems,
                                                                     AuditTelemetryPort telemetry) {
        return register(new HostPolicyFilter(properties.getWeb().getInternalHosts(), properties.getRole(), problems,
                telemetry, properties.getWeb().getMaxBodyBytes()), 1);
    }

    @Bean
    public FilterRegistrationBean<BearerAuthenticationFilter> bearerAuthenticationFilter(
            NimbusTokenVerifier verifier, ObjectProvider<RecordEventsPort> recorder, ProblemMapper problems,
            TraceIds traceIds, ObjectMapper mapper, AuditProperties properties) {
        return register(new BearerAuthenticationFilter(verifier, recorder, problems, traceIds, mapper,
                properties.getWeb().getMaxBodyBytes()), 2);
    }

    @Bean
    public FilterRegistrationBean<TenantResolutionFilter> tenantResolutionFilter(AuditProperties properties) {
        return register(new TenantResolutionFilter(properties.getWeb().getTenantHeader(),
                properties.getWeb().getEnvironmentSuffixes(), properties.getWeb().getTrustedProxies()), 3);
    }

    @Bean
    @ConditionalOnAuditRole({AuditRole.READER, AuditRole.ALL})
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(QueryRateLimiter limiter, ProblemMapper problems) {
        return register(new RateLimitFilter(limiter, problems), 4);
    }

    private static <F extends Filter> FilterRegistrationBean<F> register(F filter, int position) {
        FilterRegistrationBean<F> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(API_PATTERNS);
        registration.setOrder(BASE_ORDER + position);
        return registration;
    }
}
