package com.eudistack.audit.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import com.eudistack.audit.domain.model.EventOutcome;
import com.eudistack.audit.domain.model.RecordStatus;
import com.eudistack.audit.domain.model.RejectionReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Expected rejections: one WARN per request, or one per batch, without the payload.
 *
 * <p>HTTP 4xx follow design §3.9 ({@code error.type} is the problem type) and OBS-08. A batch that
 * answers 200 with rejected events is one line of counts (decisión propia, fase 1). A 429 is the
 * security log of AD-8, which has no documented shape (decisión propia): {@code error.type} is
 * {@code rate_limited}, plus {@code kind} and {@code search}. An invalid cursor is logged as
 * {@code invalid_cursor} and never with the cursor value; the HTTP problem stays {@code BAD_REQUEST}.
 * 429 and invalid cursors are capped at one WARN per minute and bucket.
 */
@Component
public final class SecurityLog {

    static final String LOGGER_NAME = "com.eudistack.audit.security";
    private static final String ERROR_TYPE = "error.type";
    private static final Logger LOG = LoggerFactory.getLogger(LOGGER_NAME);

    private final WarnThrottle throttle;

    public SecurityLog(Clock clock) {
        this.throttle = new WarnThrottle(clock);
    }

    /** One line for an HTTP 4xx whose problem type is already known. A 5xx is not logged here. */
    public void rejection(HttpServletRequest request, int status, String errorType) {
        if (status < 400 || status >= 500 || alreadyLogged(request)) {
            return;
        }
        mark(request, errorType);
        line(request, status).addKeyValue(ERROR_TYPE, errorType).log("Request rejected");
    }

    /**
     * AD-8. {@code kind} is {@code system} or {@code person}; {@code search} is whether the call also
     * counted against the path-search limit. The metric {@code audit.ratelimit.rejected} is unchanged.
     */
    public void rateLimited(HttpServletRequest request, boolean system, boolean search) {
        if (alreadyLogged(request)) {
            return;
        }
        String kind = system ? "system" : "person";
        WarnThrottle.Decision decision = throttle.admit("429:" + kind + ":" + search);
        mark(request, "rate_limited");
        if (!decision.emit()) {
            return;
        }
        LoggingEventBuilder builder = line(request, 429)
                .addKeyValue(ERROR_TYPE, "rate_limited")
                .addKeyValue("kind", kind)
                .addKeyValue("search", search);
        suppressed(builder, decision.suppressed()).log("Query rate limited");
    }

    /** The listing cursor could not be read. The value itself is never logged. */
    public void invalidCursor(HttpServletRequest request) {
        if (alreadyLogged(request)) {
            return;
        }
        WarnThrottle.Decision decision = throttle.admit("cursor");
        mark(request, "invalid_cursor");
        if (!decision.emit()) {
            return;
        }
        suppressed(line(request, 400).addKeyValue(ERROR_TYPE, "invalid_cursor"), decision.suppressed())
                .log("Invalid cursor");
    }

    /**
     * One line for a batch that answered 200 and rejected at least one event. Duplicates are not a
     * rejection and produce no line. Counts only; no event identifier and no payload.
     */
    public void rejectedBatch(HttpServletRequest request, List<EventOutcome> outcomes) {
        long unknownEventType = count(outcomes, RejectionReason.UNKNOWN_EVENT_TYPE);
        long invalidEvent = count(outcomes, RejectionReason.INVALID_EVENT);
        long unknownTenant = count(outcomes, RejectionReason.UNKNOWN_TENANT);
        long eventIdConflict = count(outcomes, RejectionReason.EVENT_ID_CONFLICT);
        if (unknownEventType + invalidEvent + unknownTenant + eventIdConflict == 0) {
            return;
        }
        mark(request, null);
        LoggingEventBuilder builder = LOG.atWarn()
                .addKeyValue("route", RequestRoute.normalized(request, 200))
                .addKeyValue("UnknownEventType", unknownEventType)
                .addKeyValue("InvalidEvent", invalidEvent)
                .addKeyValue("UnknownTenant", unknownTenant)
                .addKeyValue("EventIdConflict", eventIdConflict);
        tenant(builder, request).log("Batch rejected events");
    }

    private static long count(List<EventOutcome> outcomes, RejectionReason reason) {
        return outcomes.stream()
                .filter(outcome -> outcome.status() == RecordStatus.REJECTED && outcome.reason() == reason)
                .count();
    }

    private static LoggingEventBuilder line(HttpServletRequest request, int status) {
        LoggingEventBuilder builder = LOG.atWarn()
                .addKeyValue("status", status)
                .addKeyValue("route", RequestRoute.normalized(request, status));
        return tenant(builder, request);
    }

    private static LoggingEventBuilder tenant(LoggingEventBuilder builder, HttpServletRequest request) {
        if (request.getAttribute(ApiRequest.HOST_TENANT) instanceof String tenant && !tenant.isBlank()) {
            builder.addKeyValue("tenant", tenant);
        }
        return builder;
    }

    private static LoggingEventBuilder suppressed(LoggingEventBuilder builder, long suppressed) {
        if (suppressed > 0) {
            builder.addKeyValue("suppressed", suppressed);
        }
        return builder;
    }

    private static boolean alreadyLogged(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(ApiRequest.SECURITY_LOGGED));
    }

    private static void mark(HttpServletRequest request, String errorType) {
        request.setAttribute(ApiRequest.SECURITY_LOGGED, Boolean.TRUE);
        if (errorType != null) {
            request.setAttribute(ApiRequest.PROBLEM_TYPE, errorType);
        }
    }
}
