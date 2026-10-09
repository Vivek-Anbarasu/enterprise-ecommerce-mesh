package com.enterprise.mesh.security.tenant;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.enterprise.mesh.security.MeshSecurityProperties;

/**
 * Resolves the tenant for each request and publishes it to {@link TenantContext}
 * and the logging MDC.
 *
 * <p>Resolution order: JWT {@code tenant_id} claim, then the configured header.
 * Rejects the request when a tenant is required but none can be resolved, and
 * when the resolved tenant is not on the allow-list.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);

    /** MDC key mirrored into structured logs for log-based alerting. */
    public static final String MDC_TENANT = "tenant_id";

    private final MeshSecurityProperties.Tenant properties;
    private final ObjectMapper objectMapper;

    public TenantContextFilter(MeshSecurityProperties.Tenant properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String tenant = resolve(request);
        List<String> allowed = properties.getAllowed();

        if (tenant == null) {
            if (properties.isRequired()) {
                log.warn("Rejecting request: tenant required but not resolvable path={}", request.getRequestURI());
                writeProblem(response, HttpServletResponse.SC_BAD_REQUEST, "Tenant Required",
                        "No tenant could be resolved for this request. Supply the '"
                                + properties.getClaimName() + "' claim or the '"
                                + properties.getHeaderName() + "' header.");
                return;
            }
            tenant = properties.getDefaultTenant();
        } else if (!allowed.isEmpty() && !allowed.contains(tenant)) {
            log.warn("Rejecting request: tenant not allow-listed tenant={}", tenant);
            writeProblem(response, HttpServletResponse.SC_FORBIDDEN, "Tenant Not Permitted",
                    "Tenant '" + tenant + "' is not permitted to access this deployment.");
            return;
        }

        TenantContext.set(tenant);
        MDC.put(MDC_TENANT, tenant);
        response.setHeader(properties.getHeaderName(), tenant);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Guarantee no leakage into a pooled worker thread.
            TenantContext.clear();
            MDC.remove(MDC_TENANT);
        }
    }

    private String resolve(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            String fromClaim = jwtAuthenticationToken.getToken().getClaimAsString(properties.getClaimName());
            if (fromClaim != null && !fromClaim.isBlank()) {
                return fromClaim;
            }
        }
        String fromHeader = request.getHeader(properties.getHeaderName());
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader.trim();
        }
        return null;
    }

    /**
     * Writes an RFC 9457 problem document. Jackson performs the escaping, so a
     * hostile tenant identifier cannot break out of the JSON string.
     */
    private void writeProblem(HttpServletResponse response, int status, String title, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(),
                Map.of("type", "about:blank", "title", title, "status", status, "detail", detail));
    }
}
