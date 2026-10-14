package com.enterprise.mesh.bff.security;
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
 * Tells an unauthenticated SPA how to authenticate, instead of the bare 401 that a
 * browser-oriented stack would produce.
 *
 * <p>The distinction matters because there are two very different 401s here. A
 * native client that already holds a bearer token can only react to "rejected"; a
 * browser client has an actionable next step - send it to the authorization
 * endpoint. Returning {@code loginUrl} in the problem body is what lets the SPA
 * distinguish "session expired, restart the flow" from "you are banned", which
 * without it is a coin flip on the client side.
 *
 * <p>No {@code WWW-Authenticate} header: RFC 9110 reserves that for the scheme the
 * client should retry with, and this service does not accept bearer tokens at all.
 * Advertising one would send mobile clients down a path that cannot work here.
 */
@Slf4j
@RequiredArgsConstructor
public class SpaAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final URI BASE = URI.create("https://errors.enterprise-ecommerce.com/bff/");

    private final ObjectMapper objectMapper;
    private final String authorizationUri;
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        String path = request.getRequestURI();
        // Warn, never debug, and never log the Cookie header: the session cookie is
        // the credential here and it must not reach the log pipeline.
        log.warn("Unauthenticated request rejected path={} reason={}", path, authException.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "No active session. Start the sign-in flow at " + authorizationUri + ".");
        problem.setTitle("Unauthorized");
        problem.setType(BASE.resolve("unauthorized"));
        problem.setInstance(URI.create(path));

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("path", path);
        extra.put("loginUrl", authorizationUri);
        problem.setProperties(extra);

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}