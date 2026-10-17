package com.enterprise.mesh.security.tenant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

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
 *
 * <p><b>The header is only ever consulted when there is no validated token to
 * contradict it.</b> When the principal is a {@link JwtAuthenticationToken} the
 * claim is authoritative and the header is not read at all - see
 * {@link #resolve} for why, and {@link TenantHeaderRequestWrapper} for what is
 * done about the forty-odd controllers that read the header directly instead of
 * the {@link TenantContext}.
 */
@Slf4j
@RequiredArgsConstructor
public class TenantContextFilter extends OncePerRequestFilter {

    /** MDC key mirrored into structured logs for log-based alerting. */
    public static final String MDC_TENANT = "tenant_id";

    private final MeshSecurityProperties.Tenant properties;
    private final ObjectMapper objectMapper;
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
            // Downstream handlers read the tenant with @RequestHeader("X-Tenant-ID"),
            // not from TenantContext, so publishing the ThreadLocal is not on its
            // own enough to make the resolved value the only one they can observe.
            // Re-present the header with the resolved tenant instead - the same
            // strip-then-re-derive discipline TenantForwardingFilter applies at the
            // gateway, repeated here so it holds for callers that never pass there.
            filterChain.doFilter(
                    new TenantHeaderRequestWrapper(request, properties.getHeaderName(), tenant), response);
        } finally {
            // Guarantee no leakage into a pooled worker thread.
            TenantContext.clear();
            MDC.remove(MDC_TENANT);
        }
    }

    /**
     * Resolves the tenant for this request.
     *
     * <p>The important part is what it does <em>not</em> do. An earlier version fell
     * through from the claim to the {@code X-Tenant-ID} request header whenever a token
     * happened to carry no {@code tenant_id} claim. Behind the gateway that was
     * unreachable - {@code TenantForwardingFilter} strips the inbound header and
     * re-derives it from the validated token, so there is nothing left to fall back to.
     *
     * <p>It was reachable by exactly the callers defence in depth exists to distrust:
     * a pod calling this service directly, without passing the gateway, could set the
     * header to any value and have it accepted as the tenant, echoed into the response
     * header and written into the log MDC. The token itself was still fully validated,
     * so this was not an authentication bypass - but it handed an in-mesh caller
     * arbitrary tenant attribution, which is the axis every query in this mesh filters
     * on.
     *
     * <p>So a validated JWT principal is now authoritative and final: if the claim is
     * present it wins, and if it is absent the header is not consulted at all. Such a
     * request resolves to null and, with {@code required: false}, falls back to the
     * configured default tenant - which is the same answer a caller gets today through
     * the gateway. The header is still read when there is no JWT principal at all, which
     * is the anonymous and key-less local-development case where there is nothing
     * validated to contradict it.
     */
    private String resolve(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            // Authenticated by token: the claim is the whole answer. Never the header.
            String fromClaim = jwtAuthenticationToken.getToken().getClaimAsString(properties.getClaimName());
            return fromClaim == null || fromClaim.isBlank() ? null : fromClaim;
        }
        String fromHeader = request.getHeader(properties.getHeaderName());
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader.trim();
        }
        return null;
    }

    /**
     * Re-presents the tenant header so that downstream code can only observe the
     * resolved value.
     *
     * <p>Every controller in this mesh reads its tenant with
     * {@code @RequestHeader(value = "X-Tenant-ID", required = false)} rather than from
     * {@link TenantContext}, so fixing {@link #resolve} alone would change what this
     * filter publishes to the {@code ThreadLocal} without changing what the forty-odd
     * call sites actually receive. Overriding the header view closes that in one place
     * instead of forty.
     *
     * <p>Only this one header is affected; everything else is delegated untouched.
     */
    static final class TenantHeaderRequestWrapper extends HttpServletRequestWrapper {

        private final String headerName;
        private final String tenant;

        TenantHeaderRequestWrapper(HttpServletRequest request, String headerName, String tenant) {
            super(request);
            this.headerName = headerName;
            this.tenant = tenant;
        }

        @Override
        public String getHeader(String name) {
            if (headerName.equalsIgnoreCase(name)) {
                return tenant;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (headerName.equalsIgnoreCase(name)) {
                return Collections.enumeration(List.of(tenant));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            // Merge rather than replace: the resolved value has to be visible to
            // code that enumerates headers, while the rest must still come through.
            Collection<String> names = Collections.list(super.getHeaderNames());
            names.removeIf(headerName::equalsIgnoreCase);
            names.add(headerName);
            return Collections.enumeration(names);
        }
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