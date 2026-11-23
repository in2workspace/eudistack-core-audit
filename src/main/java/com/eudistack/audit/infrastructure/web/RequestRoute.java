package com.eudistack.audit.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Low-cardinality route of a request, the same values {@code http.server.requests} uses for its
 * {@code uri} tag: the matched pattern, {@code NOT_FOUND} when the status is 404 and nothing matched,
 * otherwise {@code UNKNOWN}. Never the raw URI.
 */
public final class RequestRoute {

    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String UNKNOWN = "UNKNOWN";

    private RequestRoute() {
    }

    public static String normalized(HttpServletRequest request, int status) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String value && !value.isBlank()) {
            return value;
        }
        return status == HttpStatus.NOT_FOUND.value() ? NOT_FOUND : UNKNOWN;
    }
}
