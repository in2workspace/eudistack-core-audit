package com.eudistack.audit.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.eudistack.audit.domain.exception.AuditDomainException;
import com.eudistack.audit.domain.exception.AuthenticationRequiredException;
import com.eudistack.audit.domain.exception.InvalidTokenException;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.model.EventBatch;
import com.eudistack.audit.domain.port.in.RecordEventsPort;
import com.eudistack.audit.domain.port.out.TokenVerifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;

/**
 * Authenticates the bearer token of every API request (design §3.4.1).
 *
 * <p>Missing token: 401 {@code authentication_error}. Invalid, expired, foreign issuer or audience:
 * 401 {@code invalid_token}. On the write face both leave an {@code AuditWriteDenied}
 * ({@code InvalidToken}) in {@code platform} with the size of the batch; on the read face a 401 leaves
 * nothing (no tenant, no identity — design §3.4.5).
 */
public final class BearerAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final TokenVerifier verifier;
    private final ObjectProvider<RecordEventsPort> recorder;
    private final ProblemMapper problems;
    private final TraceIds traceIds;
    private final ObjectMapper mapper;
    private final int maxBodyBytes;

    public BearerAuthenticationFilter(TokenVerifier verifier, ObjectProvider<RecordEventsPort> recorder,
                                      ProblemMapper problems, TraceIds traceIds, ObjectMapper mapper,
                                      int maxBodyBytes) {
        this.verifier = verifier;
        this.recorder = recorder;
        this.problems = problems;
        this.traceIds = traceIds;
        this.mapper = mapper;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return ApiRequest.currentFace(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        try {
            if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())
                    || header.substring(BEARER.length()).isBlank()) {
                throw new AuthenticationRequiredException("A bearer access token is required");
            }
            Caller caller = verifier.verify(header.substring(BEARER.length()).trim());
            request.setAttribute(ApiRequest.CALLER, caller);
        } catch (AuthenticationRequiredException | InvalidTokenException e) {
            if (ApiRequest.currentFace(request) == ApiRequest.Face.WRITE) {
                RecordEventsPort port = recorder.getIfAvailable();
                if (port != null) {
                    port.recordAuthenticationFailure(countEvents(request), traceIds.current());
                }
            }
            problems.write(request, response, e);
            return;
        } catch (AuditDomainException e) {
            problems.write(request, response, e);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Size of the rejected batch, for {@code AuditWriteDenied.details.event_count}: 0 when the body
     * cannot be read as JSON.
     */
    private int countEvents(HttpServletRequest request) {
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(maxBodyBytes);
            JsonNode node = mapper.readTree(body);
            return EventBatch.countEvents(node);
        } catch (IOException | RuntimeException _) {
            return 0;
        }
    }
}
