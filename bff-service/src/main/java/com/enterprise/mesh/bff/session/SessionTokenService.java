package com.enterprise.mesh.bff.session;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizationContext;
import org.springframework.security.oauth2.client.RefreshTokenOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import com.enterprise.mesh.bff.config.BffProperties;
import com.enterprise.mesh.observability.BusinessMetrics;

/**
 * Turns "a session cookie arrived" into "here is a bearer token the gateway will
 * accept", refreshing it silently when it is about to expire.
 *
 * <p>This is steps 2 to 5 of the silent-refresh flow: read the token bundle out of
 * the Redis-backed session, notice the access token has expired, spend the refresh
 * token at the identity provider, and write the new bundle back. The SPA never sees
 * any of it.
 *
 * <p>The bundle lives in the session as an {@link OAuth2AuthorizedClient}, which
 * Spring Security writes there on login. Keeping it a framework type rather than a
 * bespoke record is deliberate: the session is persisted with JDK serialization, so
 * the class name is part of the stored format, and a framework-owned type moves when
 * the framework moves.
 */
@Service
public class SessionTokenService {

    private static final Logger log = LoggerFactory.getLogger(SessionTokenService.class);

    private static final String METRIC_DOMAIN = "bff_session";

    /**
     * Entra names the directory claim {@code tid}; the rest of the mesh uses
     * {@code tenant_id}. Accept either so a token minted by an existing pipeline is
     * understood without a translation table.
     */
    private static final String TENANT_CLAIM = "tenant_id";
    private static final String TENANT_CLAIM_FALLBACK = "tid";

    /**
     * Lock striping rather than a map of per-session locks. A map keyed by session
     * id would keep an entry alive for every session the pod has ever seen, which
     * is a slow leak on a service whose whole job is handling logins. Striping
     * bounds the memory, and two unrelated shoppers colliding once in every 256 is
     * not worth the bookkeeping.
     */
    private static final int LOCK_STRIPES = 256;
    private static final Lock[] STRIPES = new Lock[LOCK_STRIPES];

    static {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            STRIPES[i] = new ReentrantLock();
        }
    }

    private final OAuth2AuthorizedClientRepository authorizedClientRepository;
    private final OAuth2AuthorizedClientProvider refreshTokenProvider;
    private final BffProperties properties;
    private final BusinessMetrics metrics;
    private final Clock clock;

    public SessionTokenService(OAuth2AuthorizedClientRepository authorizedClientRepository,
                               OAuth2AuthorizedClientProvider refreshTokenProvider,
                               BffProperties properties,
                               BusinessMetrics metrics,
                               Clock clock) {
        this.authorizedClientRepository = authorizedClientRepository;
        this.refreshTokenProvider = refreshTokenProvider;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Builds the refresh provider.
     *
     * <p>Its clock and skew are taken from the same configuration as the expiry
     * check in {@link #needsRefresh}, which makes the two agree exactly. Without
     * this, raising {@code refresh-skew} above the provider's built-in default would
     * have {@link #resolve} ask for a refresh, be handed back the unchanged client,
     * and save it - reporting a successful refresh that never happened while the
     * gateway goes on rejecting the token. Lowering it has the opposite failure,
     * spending a refresh token on a token that did not need one.
     *
     * <p>Public rather than private so the Spring {@code @Bean} in
     * {@code config} can build it with an injected clock, while a unit test can
     * still call it without standing up a context.
     */
    public static OAuth2AuthorizedClientProvider refreshTokenProvider(BffProperties properties, Clock clock) {
        RefreshTokenOAuth2AuthorizedClientProvider provider = new RefreshTokenOAuth2AuthorizedClientProvider();
        provider.setClock(clock);
        provider.setClockSkew(properties.getRefreshSkew());
        return provider;
    }

    /**
     * Resolves a live access token for the caller's session.
     *
     * @throws SessionExpiredException when there is no usable session; the caller
     *         turns this into a redirect back through the login flow
     */
    public SessionTokens resolve(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Authentication authentication) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            metrics.recordOutcome(METRIC_DOMAIN, "anonymous");
            throw new SessionExpiredException("Request carried no session cookie", true, null);
        }

        // Tomcat hands the same HttpSession instance to every concurrent request for
        // it, so holding this stripe across load-refresh-save makes two concurrent
        // requests from one browser serialise: the second re-reads the session after
        // the first has written, and finds tokens that no longer need refreshing.
        //
        // Across replicas the session objects are distinct and this stripe does not
        // help. The reload in refresh() covers that case: the losing replica sees the
        // provider reject a refresh token that rotation already consumed, re-reads,
        // and picks up the winner's tokens instead of failing the shopper.
        Lock stripe = STRIPES[Math.floorMod(session.getId().hashCode(), LOCK_STRIPES)];
        stripe.lock();
        try {
            return resolveWithinLock(request, response, session, authentication);
        } finally {
            stripe.unlock();
        }
    }

    private SessionTokens resolveWithinLock(HttpServletRequest request,
                                            HttpServletResponse response,
                                            HttpSession session,
                                            Authentication authentication) {
        OAuth2AuthorizedClient current = load(request, authentication);

        if (current == null) {
            // The session exists but the bundle is gone: the cookie outlived its
            // server-side state, or the tokens were dropped deliberately. Both mean
            // the same thing to the shopper.
            metrics.recordOutcome(METRIC_DOMAIN, "no_authorized_client");
            invalidate(session);
            throw new SessionExpiredException("Session carries no token bundle");
        }

        if (!needsRefresh(current)) {
            metrics.recordOutcome(METRIC_DOMAIN, "token_reused");
            return toTokens(current, authentication, false);
        }

        if (!properties.isRefreshEnabled()) {
            metrics.recordOutcome(METRIC_DOMAIN, "refresh_disabled");
            invalidate(session);
            throw new SessionExpiredException("Access token expired and silent refresh is disabled");
        }

        if (current.getRefreshToken() == null) {
            // Typically an Entra registration issued without `offline_access`, or a
            // scope list that silently dropped it. Worth naming precisely, because
            // the symptom otherwise surfaces hours later as an unexplained logout.
            metrics.recordOutcome(METRIC_DOMAIN, "no_refresh_token");
            invalidate(session);
            throw new SessionExpiredException(
                    "Access token expired and the identity provider never issued a refresh token");
        }

        RefreshOutcome outcome = refresh(current, request, authentication);

        if (!outcome.refreshed()) {
            // Lost the race to a concurrent request that already rotated the token
            // and wrote the result. Reusing its tokens is correct, but re-saving
            // them here would be two writers to one session, and reporting a
            // successful refresh would misreport what happened.
            metrics.recordOutcome(METRIC_DOMAIN, "token_reused");
            return toTokens(outcome.client(), authentication, false);
        }

        authorizedClientRepository.saveAuthorizedClient(outcome.client(), authentication, request, response);

        metrics.recordOutcome(METRIC_DOMAIN, "token_refreshed");
        log.info("Silently refreshed access token subject={} expiresAt={}",
                authentication.getName(), outcome.client().getAccessToken().getExpiresAt());
        return toTokens(outcome.client(), authentication, true);
    }

    private RefreshOutcome refresh(OAuth2AuthorizedClient current,
                                   HttpServletRequest request,
                                   Authentication authentication) {
        try {
            return new RefreshOutcome(refreshTokenProvider.authorize(OAuth2AuthorizationContext
                    .withAuthorizedClient(current)
                    .principal(authentication)
                    .build()), true);
        } catch (OAuth2AuthorizationException ex) {
            // A concurrent request on another replica very likely consumed the
            // refresh token first. Re-read before giving up: if that request already
            // wrote fresh tokens, this one can simply use them.
            OAuth2AuthorizedClient reloaded = load(request, authentication);
            if (reloaded != null && !needsRefresh(reloaded)) {
                log.info("Refresh token was already rotated by a concurrent request; reusing its tokens");
                metrics.recordOutcome(METRIC_DOMAIN, "refresh_raced_recovered");
                return new RefreshOutcome(reloaded, false);
            }
            metrics.recordOutcome(METRIC_DOMAIN, "refresh_rejected");
            invalidate(request.getSession(false));
            throw new SessionExpiredException(
                    "The identity provider rejected the refresh token; sign in again", false, ex);
        }
    }

    /**
     * A refresh attempt's result, distinguishing a token this call obtained from
     * one another request had already written.
     */
    private record RefreshOutcome(OAuth2AuthorizedClient client, boolean refreshed) {
    }

    /**
     * True when the access token expires inside the configured skew window.
     *
     * <p>A token with no {@code expiresAt} reports false. That is not optimism: an
     * opaque token carries no expiry for the BFF to read, so the only authority on
     * when it dies is the gateway, and guessing would mean spending a refresh token
     * on every single request.
     */
    private boolean needsRefresh(OAuth2AuthorizedClient client) {
        Instant expiresAt = client.getAccessToken().getExpiresAt();
        if (expiresAt == null) {
            return false;
        }
        return !clock.instant().isBefore(expiresAt.minus(properties.getRefreshSkew()));
    }

    private OAuth2AuthorizedClient load(HttpServletRequest request, Authentication authentication) {
        return authorizedClientRepository.loadAuthorizedClient(
                properties.getRegistrationId(), authentication, request);
    }

    private SessionTokens toTokens(OAuth2AuthorizedClient client,
                                   Authentication authentication,
                                   boolean refreshed) {
        OAuth2AccessToken token = client.getAccessToken();
        if (!OAuth2AccessToken.TokenType.BEARER.equals(token.getTokenType())) {
            // The gateway accepts exactly one credential shape. Anything else would
            // be rejected there with an opaque 401, so it is worth naming here.
            throw new SessionExpiredException(
                    "Session holds a " + token.getTokenType() + " token; only Bearer is supported");
        }
        return new SessionTokens(token.getTokenValue(), authentication.getName(),
                resolveTenant(authentication), token.getExpiresAt(), refreshed,
                client.getRefreshToken() != null);
    }

    /**
     * Best-effort tenant for logging and for the {@code X-Tenant-ID} header sent
     * downstream.
     *
     * <p>Not load-bearing for security: the api-gateway's
     * {@code TenantForwardingFilter} deletes any inbound {@code X-Tenant-ID} and
     * re-derives it from the validated access token, so a wrong or missing value
     * here cannot make the BFF impersonate another tenant. Sending it anyway keeps
     * the hop readable in a trace.
     */
    private String resolveTenant(Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken token)) {
            return null;
        }
        if (!(token.getPrincipal() instanceof OidcUser user)) {
            return null;
        }
        String tenant = user.getClaimAsString(TENANT_CLAIM);
        if (tenant == null || tenant.isBlank()) {
            tenant = user.getClaimAsString(TENANT_CLAIM_FALLBACK);
        }
        return tenant == null || tenant.isBlank() ? null : tenant;
    }

    private void invalidate(HttpSession session) {
        if (session == null) {
            return;
        }
        try {
            session.invalidate();
        } catch (IllegalStateException alreadyInvalid) {
            // A concurrent request already tore it down. Nothing left to do.
            log.debug("Session already invalidated");
        }
    }
}