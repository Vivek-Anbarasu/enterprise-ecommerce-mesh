package com.enterprise.mesh.bff.security;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.util.StringUtils;

/**
 * Renders a 403 as problem+json, and distinguishes the one 403 an SPA can actually
 * recover from.
 *
 * <p>A rejected CSRF token is the common case and it is almost always the client's
 * own doing: the page sat open long enough for the token to be replaced, or the SPA
 * posted before the cookie was written. Flagging it as {@code csrfInvalid} lets the
 * client recover; every other 403 stays generic, because telling a caller precisely
 * which authorisation rule it hit is how an attacker maps the policy.
 */
public class SpaAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(SpaAccessDeniedHandler.class);
    private static final URI BASE = URI.create("https://errors.enterprise-ecommerce.com/bff/");

    private final ObjectMapper objectMapper;
    private final String csrfCookieName;
    private final String csrfHeaderName;

    public SpaAccessDeniedHandler(ObjectMapper objectMapper,
                                  String csrfCookieName,
                                  String csrfHeaderName) {
        this.objectMapper = objectMapper;
        this.csrfCookieName = csrfCookieName;
        this.csrfHeaderName = csrfHeaderName;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {

        String path = request.getRequestURI();
        boolean csrfInvalid = accessDeniedException instanceof CsrfException;

        // Whether the *header* was absent is the question a client can act on, and
        // it is not the question Spring's exception answers. Spring reports "missing"
        // when there is no token to compare against at all - no cookie - so an SPA
        // that correctly holds the cookie but forgets the header is told its token
        // was invalid, and goes off to re-read a cookie that was never the problem.
        // Read the header directly instead.
        boolean csrfMissing = accessDeniedException instanceof MissingCsrfTokenException
                || !StringUtils.hasText(request.getHeader(csrfHeaderName));

        if (csrfInvalid) {
            log.info("CSRF check failed path={} exception={} headerSent={} remedy={}",
                    path, accessDeniedException.getClass().getSimpleName(), !csrfMissing,
                    csrfMissing ? "resend the header" : "re-read the cookie");
        } else {
            log.warn("Access denied path={} reason={}", path, accessDeniedException.getMessage());
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                csrfInvalid
                        ? "The CSRF token was missing or stale. " + remedy(csrfMissing)
                        : "You do not have permission to perform this action.");
        problem.setTitle(csrfInvalid ? "Invalid CSRF Token" : "Forbidden");
        problem.setType(BASE.resolve(csrfInvalid ? "invalid-csrf-token" : "forbidden"));
        problem.setInstance(URI.create(path));
        problem.setProperties(Map.of(
                "path", path,
                "csrfInvalid", csrfInvalid,
                "csrfMissing", csrfMissing));

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    /** Names the specific mistake, because the two fixes are different. */
    private String remedy(boolean csrfMissing) {
        return csrfMissing
                ? "Send the " + csrfHeaderName + " header, taken from the " + csrfCookieName + " cookie."
                : "Re-read the " + csrfCookieName + " cookie and resend the request.";
    }
}