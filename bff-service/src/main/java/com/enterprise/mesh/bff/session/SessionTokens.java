package com.enterprise.mesh.bff.session;

import java.time.Instant;

/**
 * The result of resolving the current session into something usable downstream.
 *
 * @param accessToken bearer token for the api-gateway; never leaves the server
 *                    except as an outbound {@code Authorization} header
 * @param subject     Entra {@code sub} of the signed-in user, stable for the life
 *                    of the session and therefore the session attribute key
 * @param tenantId    tenant from the token claims, or {@code null} for a
 *                    single-tenant deployment
 * @param expiresAt   when {@code accessToken} stops being accepted
 * @param refreshed   whether this call performed a token-endpoint round trip. Kept
 *                    on the record so callers can log and meter it without the
 *                    service having to guess at what it did
 * @param refreshable whether the session holds a refresh token at all, i.e. whether
 *                    the next expiry can be survived. Surfaced so the SPA can warn
 *                    the shopper to re-authenticate while it still can, instead of
 *                    letting the session lapse unnoticed
 */
public record SessionTokens(
        String accessToken,
        String subject,
        String tenantId,
        Instant expiresAt,
        boolean refreshed,
        boolean refreshable) {
}