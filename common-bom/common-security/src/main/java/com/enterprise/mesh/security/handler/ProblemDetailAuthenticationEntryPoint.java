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

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Returns RFC 9457 {@code application/problem+json} instead of Spring's default
 * HTML error page, so gateway and SDK clients get a predictable contract.
 */
@Slf4j
@RequiredArgsConstructor
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;
    private final URI problemBaseUri;
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        String path = request.getRequestURI();
        // Log at warn without the Authorization header to avoid credential leakage.
        log.warn("Unauthenticated request rejected path={} reason={}", path, authException.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Authentication is required to access " + path);
        problem.setTitle("Unauthorized");
        problem.setType(problemBaseUri.resolve("unauthorized"));
        problem.setInstance(URI.create(path));

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("path", path);
        extra.put("traceId", org.slf4j.MDC.get("trace_id"));
        problem.setProperties(extra);

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // Tell clients how to authenticate, per RFC 9110.
        response.setHeader("WWW-Authenticate", "Bearer");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}