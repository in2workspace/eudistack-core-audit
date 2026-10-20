package com.eudistack.audit.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;
import com.eudistack.audit.infrastructure.config.AuditRole;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The two faces of the API never cross (FR-01, FR-03, AC-08; design §3.4.1). Runs before authentication.
 *
 * <ul>
 *   <li>Internal host ({@code audit.internal.{environment}.eudistack.net}): only {@code POST /api/v1/events}.</li>
 *   <li>Tenant host ({@code {tenant}.{environment}.eudistack.net/audit}): only the two {@code GET}.</li>
 *   <li>Anything else on an API path — another method, the other face, a {@code PUT} or {@code DELETE} of an
 *       event — is a 405: no operation modifies or deletes an event.</li>
 *   <li>A browser {@code Origin} on the internal host is a 403 {@code insufficient_permission}: the writer has
 *       no CORS and is only called by platform systems. It leaves no {@code AuditWriteDenied}: the catalogue
 *       has no reason for it (PO-15); it shows in {@code audit.write.denied{reason=browser_origin}}.</li>
 * </ul>
 */
public final class HostPolicyFilter extends OncePerRequestFilter {

    private final List<Pattern> internalHosts;
    private final AuditRole role;
    private final ProblemMapper problems;
    private final AuditTelemetryPort telemetry;

    public HostPolicyFilter(List<String> internalHosts, AuditRole role, ProblemMapper problems,
                            AuditTelemetryPort telemetry) {
        this.internalHosts = internalHosts.stream()
                .map(h -> Pattern.compile(h, Pattern.CASE_INSENSITIVE)).toList();
        this.role = role;
        this.problems = problems;
        this.telemetry = telemetry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ApiRequest.isApiPath(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean internal = isInternal(request.getServerName());
        boolean collection = ApiRequest.isCollection(request);
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        if (internal) {
            if (!role.writes() || !collection || !HttpMethod.POST.matches(method)) {
                notAllowed(response, role.writes() && collection ? "POST" : null);
                return;
            }
            if (request.getHeader(HttpHeaders.ORIGIN) != null) {
                telemetry.writeDenied("browser_origin");
                problems.write(response, ProblemMapper.problem("insufficient_permission", "Insufficient permission",
                        403, "Browser requests are not accepted on the internal endpoint", ApiRequest.path(request),
                        List.of()));
                return;
            }
            request.setAttribute(ApiRequest.FACE, ApiRequest.Face.WRITE);
        } else {
            if (!role.reads() || !HttpMethod.GET.matches(method)) {
                notAllowed(response, role.reads() ? "GET" : null);
                return;
            }
            request.setAttribute(ApiRequest.FACE, ApiRequest.Face.READ);
        }
        chain.doFilter(request, response);
    }

    boolean isInternal(String host) {
        return host != null && internalHosts.stream().anyMatch(p -> p.matcher(host).matches());
    }

    private void notAllowed(HttpServletResponse response, String allow) throws IOException {
        if (allow != null) {
            response.setHeader(HttpHeaders.ALLOW, allow);
        }
        problems.write(response, ProblemMapper.problem("BAD_REQUEST", "Method not allowed", 405,
                "This operation does not exist on this endpoint", null, List.of()));
    }
}
