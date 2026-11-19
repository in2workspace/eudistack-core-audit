package com.eudistack.audit.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.DoubleSupplier;

/**
 * One operational line per API call that is an error, slow, or sampled. Decisión propia (fase 1).
 * Registered outside the other audit filters, so a host, bearer or rate-limit rejection is visible,
 * and inside the server observation, so the OTLP record keeps that trace.
 *
 * <p>A 4xx is a WARN here only when nothing else already logged that rejection. A 5xx stays an INFO:
 * the existing handler logs it as ERROR.
 */
public final class AccessLogFilter extends OncePerRequestFilter {

    static final String LOGGER_NAME = "com.eudistack.audit.access";
    private static final Logger LOG = LoggerFactory.getLogger(LOGGER_NAME);

    private final boolean enabled;
    private final Duration slowThreshold;
    private final double successSampleRate;
    private final Clock clock;
    private final DoubleSupplier random;

    public AccessLogFilter(boolean enabled, Duration slowThreshold, double successSampleRate, Clock clock,
                           DoubleSupplier random) {
        this.enabled = enabled;
        this.slowThreshold = slowThreshold;
        this.successSampleRate = successSampleRate;
        this.clock = clock;
        this.random = random;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        String path = ApiRequest.path(request);
        if ("/audit/health".equals(path) || path.startsWith("/actuator")) {
            return true;
        }
        return !path.startsWith("/api/") && !path.startsWith("/audit/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Instant started = clock.instant();
        chain.doFilter(request, response);
        if (Boolean.TRUE.equals(request.getAttribute(ApiRequest.SECURITY_LOGGED))) {
            return;
        }
        int status = response.getStatus();
        long durationMs = Duration.between(started, clock.instant()).toMillis();
        if (status >= 400 && status < 500) {
            line(LOG.atWarn(), request, status, durationMs).log("API request");
            return;
        }
        if (status >= 500 || durationMs >= slowThreshold.toMillis() || sampled()) {
            line(LOG.atInfo(), request, status, durationMs).log("API request");
        }
    }

    private LoggingEventBuilder line(LoggingEventBuilder builder, HttpServletRequest request, int status,
                                     long durationMs) {
        builder.addKeyValue("method", request.getMethod())
                .addKeyValue("route", RequestRoute.normalized(request, status))
                .addKeyValue("status", status)
                .addKeyValue("durationMs", durationMs);
        if (request.getAttribute(ApiRequest.HOST_TENANT) instanceof String tenant && !tenant.isBlank()) {
            builder.addKeyValue("tenant", tenant);
        }
        if (status >= 400 && status < 500
                && request.getAttribute(ApiRequest.PROBLEM_TYPE) instanceof String type && !type.isBlank()) {
            builder.addKeyValue("error.type", type);
        }
        return builder;
    }

    private boolean sampled() {
        if (successSampleRate <= 0.0) {
            return false;
        }
        if (successSampleRate >= 1.0) {
            return true;
        }
        return random.getAsDouble() < successSampleRate;
    }
}
