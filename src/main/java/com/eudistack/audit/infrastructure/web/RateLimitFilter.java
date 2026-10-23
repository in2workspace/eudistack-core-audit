package com.eudistack.audit.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.eudistack.audit.application.workflow.QueryRateLimiter;
import com.eudistack.audit.domain.exception.AuditDomainException;
import com.eudistack.audit.domain.model.Caller;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Query volume limit (FR-18) on the read face, after authentication and tenant resolution
 * (design §3.4.1). A path search ({@code details_path}) also counts against its own, lower limit.
 */
public final class RateLimitFilter extends OncePerRequestFilter {

    private final QueryRateLimiter limiter;
    private final ProblemMapper problems;

    public RateLimitFilter(QueryRateLimiter limiter, ProblemMapper problems) {
        this.limiter = limiter;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return ApiRequest.currentFace(request) != ApiRequest.Face.READ || request.getAttribute(ApiRequest.CALLER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            limiter.check((Caller) request.getAttribute(ApiRequest.CALLER), request.getParameter("details_path") != null);
        } catch (AuditDomainException e) {
            problems.write(request, response, e);
            return;
        }
        chain.doFilter(request, response);
    }
}
