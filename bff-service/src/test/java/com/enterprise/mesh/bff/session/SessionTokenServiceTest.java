package com.enterprise.mesh.bff.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizationContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

import com.enterprise.mesh.bff.config.BffProperties;
import com.enterprise.mesh.observability.BusinessMetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The silent-refresh path: the one piece of this service that can take a signed-in
 * shopper's session away from them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final String SUBJECT = "user-42";
    private static final Instant ISSUED_AT = Instant.parse("2026-10-13T11:00:00Z");

    @Mock
    private OAuth2AuthorizedClientRepository authorizedClientRepository;

    @Mock
    private OAuth2AuthorizedClientProvider refreshTokenProvider;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private HttpSession session;

    private BffProperties properties;
    private SessionTokenService tokenService;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        properties = new BffProperties();
        properties.setRefreshSkew(Duration.ofSeconds(90));
        properties.setRegistrationId("entra");

        tokenService = new SessionTokenService(authorizedClientRepository, refreshTokenProvider,
                properties, new BusinessMetrics(new SimpleMeterRegistry()),
                Clock.fixed(NOW, ZoneOffset.UTC));

        authentication = new UsernamePasswordAuthenticationToken(SUBJECT, "n/a", List.of());

        when(request.getSession(false)).thenReturn(session);
        when(session.getId()).thenReturn("session-abc");
    }

    // --------------------------------------------------------------- no session

    @Test
    @DisplayName("a request with no session cookie is reported as anonymous, not as a failure to debug")
    void rejectsRequestWithoutSession() {
        when(request.getSession(false)).thenReturn(null);

        assertThatThrownBy(() -> tokenService.resolve(request, response, authentication))
                .isInstanceOf(SessionExpiredException.class)
                .hasMessageContaining("no session cookie")
                .matches(ex -> ((SessionExpiredException) ex).isAnonymous());
    }

    // ------------------------------------------------------------- fresh token

    @Test
    @DisplayName("a token with plenty of life left is reused without touching the token endpoint")
    void reusesTokenThatIsNotNearExpiry() {
        givenBundle(3600, true);

        SessionTokens tokens = tokenService.resolve(request, response, authentication);

        assertThat(tokens.accessToken()).isEqualTo("access-token");
        assertThat(tokens.refreshed()).isFalse();
        assertThat(tokens.subject()).isEqualTo(SUBJECT);
        assertThat(tokens.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(tokens.refreshable()).isTrue();
        verify(refreshTokenProvider, never()).authorize(any());
        verify(authorizedClientRepository, never()).saveAuthorizedClient(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a token with 30s of headroom outside the skew window is left alone")
    void doesNotRefreshJustBeforeTheWindow() {
        // Expires in 120s against a 90s skew, so there is still 30s to spare.
        givenBundle(120, true);

        assertThat(tokenService.resolve(request, response, authentication).refreshed()).isFalse();

        verify(refreshTokenProvider, never()).authorize(any());
    }

    // ------------------------------------------------------------- the skew

    @Test
    @DisplayName("a token inside the skew window is silently refreshed and the new bundle saved")
    void refreshesTokenInsideSkewWindow() {
        // Expires in 45s: inside the 90s window, so a request arriving now would
        // otherwise reach the gateway after the token died.
        givenBundle(45, true);
        when(refreshTokenProvider.authorize(any(OAuth2AuthorizationContext.class)))
                .thenReturn(client(3600, "access-refreshed", true));

        SessionTokens tokens = tokenService.resolve(request, response, authentication);

        assertThat(tokens.refreshed()).isTrue();
        assertThat(tokens.accessToken()).isEqualTo("access-refreshed");
        assertThat(tokens.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
        // The write-back is the whole point of the exercise: without it the next
        // request would read the same dead token again.
        verify(authorizedClientRepository).saveAuthorizedClient(any(OAuth2AuthorizedClient.class),
                eq(authentication), eq(request), eq(response));
    }

    @Test
    @DisplayName("an already-expired token is refreshed")
    void refreshesExpiredToken() {
        givenBundle(-30, true);
        when(refreshTokenProvider.authorize(any(OAuth2AuthorizationContext.class)))
                .thenReturn(client(3600, "access-refreshed", true));

        assertThat(tokenService.resolve(request, response, authentication).refreshed()).isTrue();
    }

    @Test
    @DisplayName("refresh is skipped entirely while refresh is disabled, rather than sending a dying token")
    void failsFastWhenRefreshDisabled() {
        properties.setRefreshEnabled(false);
        givenBundle(-30, true);

        assertThatThrownBy(() -> tokenService.resolve(request, response, authentication))
                .isInstanceOf(SessionExpiredException.class)
                .hasMessageContaining("silent refresh is disabled");
        verify(refreshTokenProvider, never()).authorize(any());
    }

    // -------------------------------------------------------- no refresh token

    @Test
    @DisplayName("a bundle that lost its refresh token fails by name, not as an unexplained logout later")
    void failsByNameWhenRefreshTokenMissing() {
        givenBundle(-30, false);

        assertThatThrownBy(() -> tokenService.resolve(request, response, authentication))
                .isInstanceOf(SessionExpiredException.class)
                .hasMessageContaining("never issued a refresh token");
        verify(session).invalidate();
    }

    @Test
    @DisplayName("a session with no bundle at all is destroyed instead of failing repeatedly")
    void destroysSessionWithoutBundle() {
        when(authorizedClientRepository.loadAuthorizedClient(eq("entra"), any(), eq(request)))
                .thenReturn(null);

        assertThatThrownBy(() -> tokenService.resolve(request, response, authentication))
                .isInstanceOf(SessionExpiredException.class)
                .hasMessageContaining("no token bundle");
        verify(session).invalidate();
    }

    // -------------------------------------------------------- refresh refused

    @Test
    @DisplayName("a rejected refresh token ends the session with the provider's cause attached")
    void failsWhenProviderRejectsRefreshToken() {
        givenBundle(-30, true);
        when(refreshTokenProvider.authorize(any(OAuth2AuthorizationContext.class)))
                .thenThrow(new OAuth2AuthorizationException(
                        new OAuth2Error("invalid_grant", "refresh token expired", null)));

        assertThatThrownBy(() -> tokenService.resolve(request, response, authentication))
                .isInstanceOf(SessionExpiredException.class)
                .hasMessageContaining("rejected the refresh token")
                .hasCauseInstanceOf(OAuth2AuthorizationException.class);
        verify(session).invalidate();
    }

    @Test
    @DisplayName("a refresh lost to a racing request reuses the winner's tokens instead of failing the shopper")
    void recoversWhenAnotherRequestAlreadyRotatedTheRefreshToken() {
        // Two requests loaded the same stale bundle and both spent the refresh
        // token; the second is rejected because rotation consumed it. The session
        // has meanwhile been updated, so this one should pick that up.
        when(authorizedClientRepository.loadAuthorizedClient(eq("entra"), any(), eq(request)))
                .thenReturn(client(-30, "access-stale", true))
                .thenReturn(client(3600, "access-from-winner", true));
        when(refreshTokenProvider.authorize(any(OAuth2AuthorizationContext.class)))
                .thenThrow(new OAuth2AuthorizationException(
                        new OAuth2Error("invalid_grant", "refresh token already used", null)));

        SessionTokens tokens = tokenService.resolve(request, response, authentication);

        assertThat(tokens.accessToken()).isEqualTo("access-from-winner");
        assertThat(tokens.refreshed()).isFalse();
        // The other request owns the write; this one must not race it.
        verify(authorizedClientRepository, never()).saveAuthorizedClient(any(), any(), any(), any());
        verify(session, never()).invalidate();
    }

    // ------------------------------------------------------------ opaque token

    @Test
    @DisplayName("a token with no readable expiry is sent as-is rather than burning a refresh on every call")
    void doesNotGuessAtOpacity() {
        when(authorizedClientRepository.loadAuthorizedClient(eq("entra"), any(), eq(request)))
                .thenReturn(client(null, "access-opaque", true));

        SessionTokens tokens = tokenService.resolve(request, response, authentication);

        assertThat(tokens.refreshed()).isFalse();
        assertThat(tokens.expiresAt()).isNull();
        verify(refreshTokenProvider, never()).authorize(any());
    }

    // ------------------------------------------------------------ concurrency

    @Test
    @DisplayName("two threads resolving one session never both reach the token endpoint")
    void serialisesConcurrentResolutionForOneSession() throws Exception {
        // Same session id, so both threads land on the same lock stripe. The
        // second load returns a fresh bundle, as the first thread's save would.
        when(authorizedClientRepository.loadAuthorizedClient(eq("entra"), any(), eq(request)))
                .thenReturn(client(-30, "access-stale", true))
                .thenReturn(client(3600, "access-from-winner", true));
        when(refreshTokenProvider.authorize(any(OAuth2AuthorizationContext.class)))
                .thenReturn(client(3600, "access-refreshed", true));

        Runnable resolve = () -> tokenService.resolve(request, response, authentication);
        Thread first = new Thread(resolve);
        Thread second = new Thread(resolve);
        first.start();
        second.start();
        first.join();
        second.join();

        verify(refreshTokenProvider, atMost(1)).authorize(any());
    }

    // --------------------------------------------------------------- fixtures

    private void givenBundle(Integer expiresInSeconds, boolean withRefreshToken) {
        when(authorizedClientRepository.loadAuthorizedClient(eq("entra"), any(), eq(request)))
                .thenReturn(client(expiresInSeconds, "access-token", withRefreshToken));
    }

    /**
     * @param expiresInSeconds seconds from {@link #NOW}; negative is already
     *                         expired, {@code null} for a token carrying no expiry
     */
    private OAuth2AuthorizedClient client(Integer expiresInSeconds,
                                          String accessTokenValue,
                                          boolean withRefreshToken) {
        Instant expiresAt = expiresInSeconds == null ? null : NOW.plusSeconds(expiresInSeconds);
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, accessTokenValue, ISSUED_AT, expiresAt,
                Set.of("orders:read", "profile"));

        OAuth2RefreshToken refreshToken = withRefreshToken
                ? new OAuth2RefreshToken("refresh-" + accessTokenValue, ISSUED_AT, NOW.plusSeconds(86400))
                : null;

        return new OAuth2AuthorizedClient(registration(), SUBJECT, accessToken, refreshToken);
    }

    private ClientRegistration registration() {
        return ClientRegistration.withRegistrationId("entra")
                .clientId("client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .tokenUri("https://login.example.test/oauth2/v2.0/token")
                .authorizationUri("https://login.example.test/oauth2/v2.0/authorize")
                .redirectUri("{baseUrl}/bff/oauth2/callback/{registrationId}")
                .build();
    }
}