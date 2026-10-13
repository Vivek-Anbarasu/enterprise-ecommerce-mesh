package com.enterprise.mesh.bff.controller;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.enterprise.mesh.bff.api.BffViews.SessionView;
import com.enterprise.mesh.bff.config.BffSecurityConfig;
import com.enterprise.mesh.bff.session.SessionTokenService;
import com.enterprise.mesh.bff.session.SessionTokens;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session introspection.
 *
 * <p>Exists because the SPA needs three things it cannot get anywhere else: whether
 * it is signed in at all, who it is signed in as, and how long the token behind the
 * session has left. The last one lets the client refresh its own UI before the
 * session silently lapses, instead of discovering it when a request 401s.
 */
@RestController
@RequestMapping("/bff/api")
@Tag(name = "BFF Session", description = "Who am I, and for how long")
public class SessionController {

    private static final String ROLE_PREFIX = "ROLE_";

    private final SessionTokenService sessionTokenService;

    public SessionController(SessionTokenService sessionTokenService) {
        this.sessionTokenService = sessionTokenService;
    }

    @GetMapping("/session")
    @Operation(summary = "Describe the current session")
    public SessionView session(HttpServletRequest request, HttpServletResponse response,
                               Authentication authentication) {
        // Routed through the same resolve() the aggregated endpoints use, so this
        // endpoint is also a refresh trigger: a session whose access token has
        // expired gets a new one here rather than reporting an expiry that has
        // already passed.
        SessionTokens tokens = sessionTokenService.resolve(request, response, authentication);

        return new SessionView(
                tokens.subject(),
                claim(authentication, "name"),
                claim(authentication, "email"),
                tokens.tenantId(),
                roles(authentication),
                tokens.expiresAt(),
                tokens.refreshable(),
                BffSecurityConfig.AUTHORIZATION_URI);
    }

    private String claim(Authentication authentication, String name) {
        if (authentication instanceof OAuth2AuthenticationToken token
                && token.getPrincipal() instanceof OidcUser user) {
            return user.getClaimAsString(name);
        }
        return null;
    }

    private List<String> roles(Authentication authentication) {
        if (authentication == null) {
            return List.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                // ROLE_ / PERM_ prefixes are a server-side convention. Shipping them
                // would invite the SPA to branch on authority strings and break the
                // day the prefix changes.
                .map(authority -> authority.startsWith(ROLE_PREFIX)
                        ? authority.substring(ROLE_PREFIX.length())
                        : authority)
                .sorted()
                .toList();
    }
}