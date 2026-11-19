package com.eudistack.audit.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.eudistack.audit.infrastructure.config.AuditProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * One INFO line per request after the chain returns, so the status and the route pattern are known.
 * Registered inside the HTTP observation (order {@code HIGHEST_PRECEDENCE + 10}) and therefore
 * before it closes, which is when Micrometer still holds {@code traceId} in the MDC.
 */
public final class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(AccessLogFilter.class);
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern EVENT = Pattern.compile("(/api/v1/events/)[^/]+");

    private final AuditProperties.AccessLog settings;

    public AccessLogFilter(AuditProperties.AccessLog settings) {
        this.settings = settings;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!settings.isEnabled()) {
            return true;
        }
        String path = ApiRequest.path(request);
        return "/audit/health".equals(path) || path.startsWith("/audit/health/")
                || "/actuator/health".equals(path) || path.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            int status = response.getStatus();
            if (selected(status)) {
                log(request, status, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
        }
    }

    private boolean selected(int status) {
        if (status >= 400) {
            return true;
        }
        double sample = settings.getSample();
        if (sample >= 1.0) {
            return true;
        }
        if (sample <= 0.0) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble() < sample;
    }

    private static void log(HttpServletRequest request, int status, long durationMs) {
        var event = LOG.atInfo()
                .addKeyValue("http.method", request.getMethod())
                .addKeyValue("http.route", route(request))
                .addKeyValue("http.status_code", status)
                .addKeyValue("duration_ms", durationMs);
        Object tenant = request.getAttribute(ApiRequest.HOST_TENANT);
        if (tenant instanceof String value && !value.isBlank()) {
            event.addKeyValue("tenant", value);
        }
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            event.addKeyValue("trace_id", traceId);
        }
        event.log("http request");
    }

    /**
     * The matched pattern when the dispatcher set one; otherwise the path with ids collapsed.
     * The query string is never part of either.
     */
    static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String value && !value.isBlank()) {
            return value;
        }
        return EVENT.matcher(UUID.matcher(ApiRequest.path(request)).replaceAll("{id}")).replaceAll("$1{id}");
    }
}
