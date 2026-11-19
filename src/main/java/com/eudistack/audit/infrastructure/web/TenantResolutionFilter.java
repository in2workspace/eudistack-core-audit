package com.eudistack.audit.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Publishes the tenant of a query ({@link HostTenantResolver}) for the controllers. Whether that tenant
 * exists and is the caller's is decided later, by the scope policy, which records the denial.
 */
public final class TenantResolutionFilter extends OncePerRequestFilter {

    private final HostTenantResolver resolver;

    public TenantResolutionFilter(HostTenantResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return ApiRequest.currentFace(request) != ApiRequest.Face.READ;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(ApiRequest.HOST_TENANT, resolver.resolve(request));
        chain.doFilter(request, response);
    }
}
