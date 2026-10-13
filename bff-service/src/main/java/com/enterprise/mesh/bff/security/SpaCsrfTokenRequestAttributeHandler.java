package com.enterprise.mesh.bff.security;

import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

/**
 * Lets a single-page app read the CSRF token from its cookie and echo it back.
 *
 * <p>Spring Security 6 renders CSRF tokens into the HTML as a XOR-masked value so
 * that a token sitting in a page cannot be recovered from a BREACH-style side
 * channel. An SPA has no template to render it into; it reads the cookie and
 * replays it in a header. Those are incompatible as shipped.
 *
 * <p>The split below keeps the protection where it belongs - the value rendered
 * into any server-generated response is still masked, and a token that is
 * <em>absent</em> from the header still has to be unmasked before comparison - but
 * accepts the raw cookie value when the client sends it in the header. Without
 * this the SPA's first POST fails with a 403 that looks like a CORS problem.
 */
public final class SpaCsrfTokenRequestAttributeHandler extends CsrfTokenRequestAttributeHandler {

    private final CsrfTokenRequestHandler delegate = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        this.delegate.handle(request, response, csrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        // Header present -> the client replayed the raw cookie value, so compare it raw.
        if (StringUtils.hasText(request.getHeader(csrfToken.getHeaderName()))) {
            return super.resolveCsrfTokenValue(request, csrfToken);
        }
        // Header absent -> a form post carrying the masked value; unmask it.
        return this.delegate.resolveCsrfTokenValue(request, csrfToken);
    }
}