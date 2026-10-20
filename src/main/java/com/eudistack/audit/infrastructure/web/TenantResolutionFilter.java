package com.eudistack.audit.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Takes the tenant of a query from where it arrives, never from the token or the query string
 * (PRD §5): the header a trusted ingress sets, or else the first label of the host, without
 * environment suffix ({@code red-logistica.stg.eudistack.net} and {@code sandbox-stg.eudistack.net}
 * both work, as in the Issuer's {@code TenantIdentifiers}). Whether that tenant exists and is the
 * caller's is decided later, by the scope policy, which records the denial.
 */
public final class TenantResolutionFilter extends OncePerRequestFilter {

    private static final Pattern LABEL = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");
    private static final Pattern IP_ADDRESS = Pattern.compile("^[0-9.]+$|^\\[.*]$");

    private final String tenantHeader;
    private final List<String> environmentSuffixes;

    public TenantResolutionFilter(String tenantHeader, List<String> environmentSuffixes) {
        this.tenantHeader = tenantHeader == null || tenantHeader.isBlank() ? null : tenantHeader;
        this.environmentSuffixes = List.copyOf(environmentSuffixes);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return ApiRequest.face(request) != ApiRequest.Face.READ;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(ApiRequest.HOST_TENANT, resolve(request));
        chain.doFilter(request, response);
    }

    String resolve(HttpServletRequest request) {
        String fromHeader = tenantHeader == null ? null : request.getHeader(tenantHeader);
        if (fromHeader != null && !fromHeader.isBlank()) {
            return clean(fromHeader);
        }
        String host = request.getServerName();
        if (host == null || IP_ADDRESS.matcher(host).matches() || !host.contains(".")) {
            return null;
        }
        return clean(host.substring(0, host.indexOf('.')));
    }

    private String clean(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (String suffix : environmentSuffixes) {
            if (value.endsWith(suffix) && value.length() > suffix.length()) {
                value = value.substring(0, value.length() - suffix.length());
                break;
            }
        }
        return LABEL.matcher(value).matches() ? value : null;
    }
}
