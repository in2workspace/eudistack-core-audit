package com.eudistack.audit.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Maps the domain exceptions to the problems of the contract (design §3.9) and writes them, both from
 * the filters and from {@code AuditExceptionHandler}.
 */
public final class ProblemMapper {

    /** Seconds the producer waits before retrying after a 503. */
    public static final long UNAVAILABLE_RETRY_AFTER = 5;

    private final ObjectMapper mapper;

    public ProblemMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record Mapped(Problem problem, Map<String, String> headers) {
    }

    public Mapped map(AuditDomainException exception, String instance) {
        return switch (exception) {
            case BadRequestException e -> plain(problem("BAD_REQUEST", "The request is not valid", 400,
                    e.getMessage(), instance, Problem.of(e.violations())));
            case UnknownEventTypeException e -> plain(problem("unknown_event_type", "Unknown event type", 400,
                    e.getMessage(), instance, List.of()));
            case PathNotSearchableException e -> plain(problem("path_not_searchable", "The path is not searchable",
                    400, e.getMessage(), instance, List.of()));
            case AuthenticationRequiredException e -> new Mapped(problem("authentication_error",
                    "Authentication required", 401, e.getMessage(), instance, List.of()),
                    Map.of(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
            case InvalidTokenException e -> new Mapped(problem("invalid_token", "The access token is not valid", 401,
                    e.getMessage(), instance, List.of()),
                    Map.of(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""));
            case InsufficientPermissionException e -> plain(problem("insufficient_permission",
                    "Insufficient permission", 403, e.getMessage(), instance, List.of()));
            case TenantMismatchException e -> plain(problem("tenant_mismatch", "Tenant mismatch", 403, e.getMessage(),
                    instance, List.of()));
            case TenantNotResolvedException e -> plain(problem("tenant_not_resolved", "Tenant not resolved", 403,
                    e.getMessage(), instance, List.of()));
            case EventNotFoundException e -> plain(problem("event_not_found", "Event not found", 404,
                    "The event does not exist, is outside your scope or is archived", instance, List.of()));
            case PeriodArchivedException e -> plain(problem("period_archived", "The period is archived", 409,
                    "Oldest instant still online: " + e.oldestOnline(), instance, List.of()));
            case RateLimitedException e -> new Mapped(problem("rate_limited", "Too many queries", 429,
                    "The volume limit was exceeded", instance, List.of()),
                    Map.of(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds())));
            case ServiceUnavailableException e -> new Mapped(problem("service_unavailable",
                    "The audit store is not available", 503,
                    "The request had no effect. Retry after Retry-After", instance, List.of()),
                    Map.of(HttpHeaders.RETRY_AFTER, Long.toString(UNAVAILABLE_RETRY_AFTER)));
        };
    }

    public static Problem problem(String type, String title, int status, String detail, String instance,
                                  List<Problem.ViolationBody> violations) {
        return new Problem(type, title, status, detail, instance, violations);
    }

    public void write(HttpServletRequest request, HttpServletResponse response, AuditDomainException exception)
            throws IOException {
        Mapped mapped = map(exception, ApiRequest.path(request));
        mapped.headers().forEach(response::setHeader);
        write(response, mapped.problem());
    }

    public void write(HttpServletResponse response, Problem problem) throws IOException {
        response.setStatus(problem.status());
        response.setContentType(Problem.MEDIA_TYPE);
        mapper.writeValue(response.getOutputStream(), problem);
    }

    private static Mapped plain(Problem problem) {
        return new Mapped(problem, Map.of());
    }
}
