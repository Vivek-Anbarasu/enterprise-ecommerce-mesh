package com.enterprise.mesh.security.handler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.enterprise.mesh.observability.TraceIds;

/**
 * Renders authorization failures as RFC 9457 problem documents and
 * distinguishes 401 (anonymous) from 403 (authenticated but not permitted).
 */
@Slf4j
@RequiredArgsConstructor
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final URI problemBaseUri;
    private final TraceIds traceIds;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean anonymous = authentication == null
                || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal());

        int status = anonymous ? HttpServletResponse.SC_UNAUTHORIZED : HttpServletResponse.SC_FORBIDDEN;
        String title = anonymous ? "Unauthorized" : "Forbidden";
        String detail = anonymous
                ? "Authentication is required to access " + request.getRequestURI()
                : "The authenticated principal is not permitted to perform this operation";

        log.warn("Access denied path={} status={} principal={}", request.getRequestURI(), status,
                anonymous ? "anonymous" : authentication.getName());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setTitle(title);
        problem.setType(problemBaseUri.resolve(title.toLowerCase()));
        problem.setInstance(URI.create(request.getRequestURI()));

        // LinkedHashMap, not Map.of: Map.of rejects null values, and traceId is
        // legitimately absent when tracing is disabled. Absent means the property
        // is omitted - never the string "null", which is what this handler used
        // to return because it read an MDC key nothing writes.
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", request.getRequestURI());
        traceIds.applyTo(properties);
        problem.setProperties(properties);

        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        if (anonymous) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}