package com.eudistack.audit.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import com.eudistack.audit.domain.exception.AuditDomainException;
import com.eudistack.audit.domain.exception.ServiceUnavailableException;
import com.eudistack.audit.infrastructure.config.AuditRole;
import com.eudistack.audit.infrastructure.config.ConditionalOnAuditRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Translates every exception that reaches the controllers into {@code application/problem+json}
 * (RFC 9457, design §3.9). The problem never carries a stack trace, SQL or a token.
 */
@RestControllerAdvice
@ConditionalOnAuditRole({AuditRole.WRITER, AuditRole.READER, AuditRole.ALL})
public class AuditExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AuditExceptionHandler.class);
    private static final MediaType PROBLEM = MediaType.parseMediaType(Problem.MEDIA_TYPE);

    private final ProblemMapper problems;

    public AuditExceptionHandler(ProblemMapper problems) {
        this.problems = problems;
    }

    @ExceptionHandler(AuditDomainException.class)
    public ResponseEntity<Problem> domain(AuditDomainException exception, HttpServletRequest request) {
        if (exception instanceof ServiceUnavailableException) {
            LOG.error("Audit store unavailable: {}", exception.getMessage(), exception.getCause());
        }
        ProblemMapper.Mapped mapped = problems.map(exception, ApiRequest.path(request));
        HttpHeaders headers = new HttpHeaders();
        mapped.headers().forEach(headers::set);
        return ResponseEntity.status(mapped.problem().status()).headers(headers).contentType(PROBLEM)
                .body(mapped.problem());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Problem> dataAccess(DataAccessException exception, HttpServletRequest request) {
        return domain(new ServiceUnavailableException("The audit store is not available", exception), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Problem> unreadable(HttpServletRequest request) {
        return respond(ProblemMapper.problem("BAD_REQUEST", "The request is not valid", 400,
                "The body could not be read", ApiRequest.path(request), List.of()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Problem> methodNotAllowed(HttpServletRequest request) {
        return respond(ProblemMapper.problem("BAD_REQUEST", "Method not allowed", 405,
                "This operation does not exist", ApiRequest.path(request), List.of()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Problem> notFound(HttpServletRequest request) {
        return respond(ProblemMapper.problem("NOT_FOUND", "Not found", 404, null, ApiRequest.path(request),
                List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Problem> unexpected(Exception exception, HttpServletRequest request) {
        LOG.error("Unexpected error on {} {}", request.getMethod(), ApiRequest.path(request), exception);
        return respond(ProblemMapper.problem("INTERNAL_SERVER_ERROR", "Unexpected error",
                HttpStatus.INTERNAL_SERVER_ERROR.value(), null, ApiRequest.path(request), List.of()));
    }

    private static ResponseEntity<Problem> respond(Problem problem) {
        return ResponseEntity.status(problem.status()).contentType(PROBLEM).body(problem);
    }
}
