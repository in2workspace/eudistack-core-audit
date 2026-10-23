package com.eudistack.audit.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import com.eudistack.audit.domain.exception.AuthenticationRequiredException;
import com.eudistack.audit.domain.model.Caller;
import org.springframework.web.util.UrlPathHelper;

import java.util.regex.Pattern;

/**
 * Request attributes shared by the filters and the controllers, and recognition of the API paths.
 *
 * <p>The query host publishes the API under {@code /audit} (OpenAPI {@code servers}). Whether the
 * ingress strips that prefix is open (PO-10), so both {@code /api/v1/events} and
 * {@code /audit/api/v1/events} are served.
 *
 * <p>Paths are matched decoded, without {@code ;} parameters and with repeated slashes merged — what
 * the dispatcher routes. Matching the raw request URI would let {@code /api/v1/%65vents} or
 * {@code /api/v1/events;x=1} reach a controller while every filter skipped it.
 */
public final class ApiRequest {

    public static final String CALLER = "audit.caller";
    public static final String HOST_TENANT = "audit.hostTenant";
    public static final String FACE = "audit.face";
    public static final String COLLECTION = "/api/v1/events";
    public static final String PREFIX = "/audit";

    private static final Pattern API_PATH = Pattern.compile("^(?:/audit)?/api/v1/events(?:/[^/]+)?/?$");
    private static final UrlPathHelper PATHS = new UrlPathHelper();

    /** Which face of the API a request reaches. */
    public enum Face { WRITE, READ }

    private ApiRequest() {
    }

    public static boolean isApiPath(HttpServletRequest request) {
        return API_PATH.matcher(path(request)).matches();
    }

    public static boolean isCollection(HttpServletRequest request) {
        String path = path(request);
        if (path.startsWith(PREFIX + "/")) {
            path = path.substring(PREFIX.length());
        }
        return COLLECTION.equals(path) || (COLLECTION + "/").equals(path);
    }

    public static String path(HttpServletRequest request) {
        return PATHS.getPathWithinApplication(request);
    }

    public static Face currentFace(HttpServletRequest request) {
        return (Face) request.getAttribute(FACE);
    }

    /**
     * The caller authenticated by {@link BearerAuthenticationFilter}. A controller reached without one
     * answers 401 instead of running unauthenticated.
     */
    public static Caller authenticatedCaller(HttpServletRequest request) {
        if (request.getAttribute(CALLER) instanceof Caller caller) {
            return caller;
        }
        throw new AuthenticationRequiredException("A bearer access token is required");
    }
}
