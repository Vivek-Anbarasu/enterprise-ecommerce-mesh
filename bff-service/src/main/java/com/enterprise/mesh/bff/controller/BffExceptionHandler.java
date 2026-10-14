package com.enterprise.mesh.bff.controller;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.Map;

import com.enterprise.mesh.bff.client.DownstreamException;
import com.enterprise.mesh.bff.client.DownstreamUnauthorizedException;
import com.enterprise.mesh.bff.config.BffSecurityConfig;
import com.enterprise.mesh.bff.session.SessionExpiredException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps BFF failures to RFC 9457 problem documents, matching the contract every
 * other service in the mesh already publishes.
 */
@RestControllerAdvice
@Slf4j
public class BffExceptionHandler {

    private static final URI BASE = URI.create("https://errors.enterprise-ecommerce.com/bff/");

    /**
     * The session is gone or unusable. 401 with the login URL, because this is the
     * one failure the client can actually do something about.
     */
    @ExceptionHandler(SessionExpiredException.class)
    public ResponseEntity<ProblemDetail> handleSessionExpired(SessionExpiredException ex) {
        log.info("Session unusable reason={} anonymous={}", ex.getMessage(), ex.isAnonymous());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setTitle(ex.isAnonymous() ? "Not Signed In" : "Session Expired");
        problem.setType(BASE.resolve(ex.isAnonymous() ? "anonymous" : "session-expired"));
        problem.setProperty("loginUrl", BffSecurityConfig.AUTHORIZATION_URI);
        // The cookie is dead server-side; tell the browser to drop it so it stops
        // being sent on every subsequent request.
        problem.setProperty("reauthenticate", true);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    /**
     * The gateway rejected a token this BFF believed was good. Surfaces as 503
     * rather than 401 on purpose: the shopper is still signed in, and sending them
     * through the login flow would paper over a clock skew or a key rotation that
     * the operator needs to see.
     */
    @ExceptionHandler(DownstreamUnauthorizedException.class)
    public ResponseEntity<ProblemDetail> handleStaleToken(DownstreamUnauthorizedException ex) {
        log.warn("Access token rejected downstream path={}", ex.getPath());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Your session could not be verified. Please try again in a moment.");
        problem.setTitle("Session Verification Failed");
        problem.setType(BASE.resolve("stale-token"));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    /**
     * Last-resort handler for a section that somehow escaped the per-section
     * degradation. Keeps the response a problem document instead of the container's
     * default error page.
     */
    @ExceptionHandler(DownstreamException.class)
    public ResponseEntity<ProblemDetail> handleDownstream(DownstreamException ex) {
        log.warn("Downstream call failed path={} status={}", ex.getPath(), ex.getStatus());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, ex.getMessage());
        problem.setTitle("Upstream Service Unavailable");
        problem.setType(BASE.resolve("downstream-unavailable"));
        problem.setProperties(Map.of("path", ex.getPath()));
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problem);
    }
}