package com.eudistack.audit.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Takes the tenant of a query from where it arrives, never from the token or the query string
 * (PRD §5): the header a trusted ingress sets, or else the first label of the host, without
 * environment suffix ({@code red-logistica.stg.eudistack.net} and {@code sandbox-stg.eudistack.net}
 * both work, as in the Issuer's {@code TenantIdentifiers}).
 *
 * <p>Shared by the filter that publishes it for the scope policy and by the authentication, which needs
 * it to resolve the tenant marker of the accepted audiences (design AD-11).
 */
public final class HostTenantResolver {

    private static final Pattern LABEL = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");
    private static final Pattern IP_ADDRESS = Pattern.compile("^[0-9.]+$|^\\[.*]$");

    private final String tenantHeader;
    private final List<String> environmentSuffixes;
    private final List<String> trustedProxies;

    public HostTenantResolver(String tenantHeader, List<String> environmentSuffixes, List<String> trustedProxies) {
        this.tenantHeader = tenantHeader == null || tenantHeader.isBlank() ? null : tenantHeader;
        this.environmentSuffixes = List.copyOf(environmentSuffixes);
        this.trustedProxies = List.copyOf(trustedProxies);
    }

    /**
     * @return the tenant identifier, or {@code null} when the request carries none
     */
    public String resolve(HttpServletRequest request) {
        String fromHeader = headerFromTrustedProxy(request);
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

    private String headerFromTrustedProxy(HttpServletRequest request) {
        if (tenantHeader == null || !trustedProxies.contains(request.getRemoteAddr())) {
            return null;
        }
        return request.getHeader(tenantHeader);
    }
}
