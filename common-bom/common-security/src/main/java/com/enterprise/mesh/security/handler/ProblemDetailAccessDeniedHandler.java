package com.enterprise.mesh.security.handler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Renders authorization failures as RFC 9457 problem documents and
 * distinguishes 401 (anonymous) from 403 (authenticated but not permitted).
 */
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailAccessDeniedHandler.class);

    private final ObjectMapper objectMapper;
    private final URI problemBaseUri;

    public ProblemDetailAccessDeniedHandler(ObjectMapper objectMapper, URI problemBaseUri) {
        this.objectMapper = objectMapper;
        this.problemBaseUri = problemBaseUri;
    }

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
        problem.setProperties(Map.of(
                "path", request.getRequestURI(),
                "traceId", String.valueOf(org.slf4j.MDC.get("trace_id"))));

        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        if (anonymous) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
