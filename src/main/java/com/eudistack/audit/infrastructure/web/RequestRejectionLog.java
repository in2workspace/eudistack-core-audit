package com.eudistack.audit.infrastructure.web;

import com.eudistack.audit.domain.exception.AuditDomainException;
import com.eudistack.audit.domain.exception.AuthenticationRequiredException;
import com.eudistack.audit.domain.exception.BadRequestException;
import com.eudistack.audit.domain.exception.EventNotFoundException;
import com.eudistack.audit.domain.exception.InsufficientPermissionException;
import com.eudistack.audit.domain.exception.InvalidTokenException;
import com.eudistack.audit.domain.exception.PathNotSearchableException;
import com.eudistack.audit.domain.exception.PeriodArchivedException;
import com.eudistack.audit.domain.exception.RateLimitedException;
import com.eudistack.audit.domain.exception.ServiceUnavailableException;
import com.eudistack.audit.domain.exception.TenantMismatchException;
import com.eudistack.audit.domain.exception.TenantNotResolvedException;
import com.eudistack.audit.domain.exception.UnknownEventTypeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Expected client rejections. The message carries the problem code and the status, never the
 * exception text: that text can echo a field value, a token or a cursor.
 */
public final class RequestRejectionLog {

    private static final Logger LOG = LoggerFactory.getLogger(RequestRejectionLog.class);

    private RequestRejectionLog() {
    }

    public static void log(AuditDomainException exception) {
        int status = status(exception);
        if (status >= 500) {
            return;
        }
        String problem = exception instanceof BadRequestException bad && cursor(bad)
                ? "cursor_invalid" : problem(exception);
        write(status, problem);
    }

    /** A rejection written by a filter, which already has the problem code and no exception text. */
    public static void clientError(int status, String problem) {
        if (status >= 500) {
            return;
        }
        write(status, problem);
    }

    private static void write(int status, String problem) {
        var event = status == 404 ? LOG.atInfo() : LOG.atWarn();
        event.addKeyValue("http.status_code", status).addKeyValue("problem", problem).log("request rejected");
    }

    private static boolean cursor(BadRequestException exception) {
        return exception.violations().stream().anyMatch(violation -> "cursor".equals(violation.field()));
    }

    private static int status(AuditDomainException exception) {
        return switch (exception) {
            case BadRequestException _, UnknownEventTypeException _, PathNotSearchableException _ -> 400;
            case AuthenticationRequiredException _, InvalidTokenException _ -> 401;
            case InsufficientPermissionException _, TenantMismatchException _, TenantNotResolvedException _ -> 403;
            case EventNotFoundException _ -> 404;
            case PeriodArchivedException _ -> 409;
            case RateLimitedException _ -> 429;
            case ServiceUnavailableException _ -> 503;
        };
    }

    private static String problem(AuditDomainException exception) {
        return switch (exception) {
            case BadRequestException _ -> "bad_request";
            case UnknownEventTypeException _ -> "unknown_event_type";
            case PathNotSearchableException _ -> "path_not_searchable";
            case AuthenticationRequiredException _ -> "authentication_error";
            case InvalidTokenException _ -> "invalid_token";
            case InsufficientPermissionException _ -> "insufficient_permission";
            case TenantMismatchException _ -> "tenant_mismatch";
            case TenantNotResolvedException _ -> "tenant_not_resolved";
            case EventNotFoundException _ -> "event_not_found";
            case PeriodArchivedException _ -> "period_archived";
            case RateLimitedException _ -> "rate_limited";
            case ServiceUnavailableException _ -> "service_unavailable";
        };
    }
}
