package com.eudistack.audit.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import com.eudistack.audit.domain.exception.BadRequestException;
import com.eudistack.audit.domain.port.out.AuditTelemetryPort;
import com.eudistack.audit.infrastructure.config.AuditRole;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    private final int maxBodyBytes;

    public HostPolicyFilter(List<String> internalHosts, AuditRole role, ProblemMapper problems,
                            AuditTelemetryPort telemetry, int maxBodyBytes) {
        this.internalHosts = internalHosts.stream()
                .map(h -> Pattern.compile(h, Pattern.CASE_INSENSITIVE)).toList();
        this.role = role;
        this.problems = problems;
        this.telemetry = telemetry;
        this.maxBodyBytes = maxBodyBytes;
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
            if (!acceptWrite(request, response, collection, method)) {
                return;
            }
            chain.doFilter(new BoundedBodyRequest(request, maxBodyBytes), response);
            return;
        } else if (!acceptRead(request, response, method)) {
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean acceptWrite(HttpServletRequest request, HttpServletResponse response, boolean collection,
                                String method) throws IOException {
        if (!role.writes() || !collection || !HttpMethod.POST.matches(method)) {
            notAllowed(response, role.writes() && collection ? "POST" : null);
            return false;
        }
        if (request.getHeader(HttpHeaders.ORIGIN) != null) {
            telemetry.writeDenied("browser_origin");
            problems.write(response, ProblemMapper.problem("insufficient_permission", "Insufficient permission",
                    403, "Browser requests are not accepted on the internal endpoint", ApiRequest.path(request),
                    List.of()));
            return false;
        }
        long length = request.getContentLengthLong();
        if (length < 0 || length > maxBodyBytes) {
            problems.write(response, ProblemMapper.problem("BAD_REQUEST", "The request is not valid", 400,
                    "The body must declare Content-Length and not exceed " + maxBodyBytes + " bytes",
                    ApiRequest.path(request), List.of()));
            return false;
        }
        request.setAttribute(ApiRequest.FACE, ApiRequest.Face.WRITE);
        return true;
    }

    private boolean acceptRead(HttpServletRequest request, HttpServletResponse response, String method)
            throws IOException {
        if (!role.reads() || !HttpMethod.GET.matches(method)) {
            notAllowed(response, role.reads() ? "GET" : null);
            return false;
        }
        request.setAttribute(ApiRequest.FACE, ApiRequest.Face.READ);
        return true;
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

    /**
     * Stops a body whose {@code Content-Length} understates what is actually sent.
     */
    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {

        private final int max;
        private ServletInputStream stream;

        private BoundedBodyRequest(HttpServletRequest request, int max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                byte[] read = super.getInputStream().readNBytes(max + 1);
                if (read.length > max) {
                    throw new BadRequestException("", "The body must not exceed " + max + " bytes");
                }
                stream = new CachedServletInputStream(read);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    private static final class CachedServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream input;

        private CachedServletInputStream(byte[] body) {
            this.input = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return input.read();
        }

        @Override
        public boolean isFinished() {
            return input.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("Async reads are not used");
        }
    }
}
