package com.enterprise.mesh.bff.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Forces the deferred CSRF token to materialise on every request.
 *
 * <p>{@code CsrfFilter} only resolves the token when something asks for it, and on a
 * plain GET nothing does - there is no template to render it into. The cookie is
 * therefore never written on the first request, so the SPA loads, finds no
 * {@code XSRF-TOKEN} cookie, and fails every subsequent POST with a 403. Touching
 * the token here makes the cookie appear on the response that first needed it.
 *
 * <p>Must be registered after {@code CsrfFilter}, otherwise there is nothing to
 * touch.
 */
public final class CsrfCookieWritingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken();
        }
        filterChain.doFilter(request, response);
    }
}