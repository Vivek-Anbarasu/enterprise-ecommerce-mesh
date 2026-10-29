# ADR-0005: The BFF holds the token bundle; the browser holds only a session cookie

- **Status:** Accepted
- **Date:** 2026-10-29
- **Scope:** `bff-service`, `api-gateway`

## Context

The storefront is a single-page application. Sign-in is OIDC against Entra ID, and every
business call must carry a bearer token that `api-gateway` validates as an OAuth2 resource
server.

The obvious implementation puts the token in the SPA: complete the code flow in
JavaScript, keep the access and refresh tokens in memory or `localStorage`, and attach
`Authorization: Bearer` to each call. It is also the implementation that turns any XSS bug
into full account takeover — `localStorage` is readable by every script on the origin, and
a refresh token there is a long-lived credential.

There is a second, subtler problem: silent refresh. If the SPA owns the refresh token, it
must also own the refresh logic, the expiry clock, and the failure path. That logic is
security-critical and duplicated in every client.

## Decision

**A backend-for-frontend owns the tokens. The browser owns a session cookie.**

- `bff-service` performs the OIDC code flow and stores the resulting
  `OAuth2AuthorizedClient` bundle (access token, refresh token, expiry) in the
  **Redis-backed HTTP session**.
- The SPA receives an **HttpOnly, `Secure`, `SameSite=Lax` session cookie** named `SESSION`.
  It is HttpOnly so script cannot read it. It is `Lax` rather than `Strict` because the
  OAuth2 callback arrives as a top-level redirect from Entra, and `Strict` would withhold
  the cookie from that redirect and break sign-in.
- On each business call, `SessionTokenService` reads the bundle from the session, refreshes
  it silently if it is about to expire, writes the new bundle back, and re-issues it to the
  gateway as a bearer token. The SPA never sees a token.
- The session store is Redis, not pod memory, so any replica can serve any request. The
  session key is namespaced (`bff:session`) and expires a few minutes after the idle
  timeout, so a replica with a slightly ahead clock cannot delete a still-valid session.
- **The gateway remains a resource server.** It validates the token independently; it does
  not trust the BFF's assertion that the user is authenticated.
- The gateway pins **both** `issuer-uri` and `jwk-set-uri`. Discovering the JWKS without
  pinning the issuer would accept a token minted for any tenant in the directory.
- **Two edge entry points, one certificate, routed by path**: the SPA's `/bff` and `/login`
  go to the BFF; everything else goes straight to the gateway. An unrecognised path fails
  safe at the edge rather than 404ing.

## Consequences

### Positive

- An XSS bug can make requests as the user while the page is open, but it cannot
  exfiltrate a token or a refresh token. The credential never exists in JavaScript.
- Silent refresh is implemented once, server-side, in a framework type
  (`OAuth2AuthorizedClient`) rather than a bespoke record — the session is persisted with
  JDK serialization, so a hand-rolled type would have to be serialization-stable.
- Logout is central: deleting the session deletes the token bundle.
- Multiple frontends can share the same BFF without each implementing OIDC.

### Negative

- **One extra hop.** The SPA calls the BFF, the BFF calls the gateway. That is a real
  latency cost on every request, and the BFF must be scaled and monitored as a first-class
  service.
- **Redis is on the critical path for authentication.** A session-store outage is a
  sign-in outage, even though catalogue reads are anonymous.
- **CSRF becomes relevant**, because the browser now sends a cookie automatically. The
  session cookie's `SameSite=Lax` and the gateway's method scoping are the mitigations;
  a stricter posture would add a CSRF token.
- Session objects are serialized, so any class placed in the session must remain
  serialization-compatible across deployments.

### Operational

- The BFF uses virtual threads because its aggregation fan-out is blocking HTTP; this keeps
  the servlet session model without paying a thread per fan-out.
- `SESSION_COOKIE_SECURE` must remain `true` outside local development.
- The gateway's `entra` profile is required for real token validation; without it the
  gateway starts without a `JwtDecoder` and logs a warning. That is a deliberate
  fail-closed default, not a bug.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Tokens in the SPA** (`localStorage` or memory) | Any XSS becomes token exfiltration, and a refresh token there is long-lived. Also duplicates refresh logic in every client. |
| **Token handler pattern in the browser** | Same threat model as a BFF but implemented in JavaScript: the token still reaches the browser, just via a different route. |
| **Session on the gateway only** | The gateway would need to be an OAuth2 *client* as well as a resource server, mixing edge routing with interactive login and making the gateway stateful. |
| **Cookie-based auth end to end** (no bearer token) | Would remove the token hop, but gives up the resource-server model the services already use and makes cross-service calls cookie-dependent. |

## Revisit triggers

- Native or mobile clients need to call the gateway directly, which the BFF flow does not
  serve.
- BFF latency or availability becomes the dominant term in the request budget.
- The platform moves to cookie-based authentication end to end, making the token exchange
  redundant.
- A CSRF incident or audit finding requires a token-based CSRF defence in addition to
  `SameSite`.

## References

- `bff-service/src/main/java/com/enterprise/mesh/bff/session/SessionTokenService.java`
- `bff-service/src/main/resources/application.yml` (session cookie and Redis session store)
- `api-gateway/src/main/java/com/enterprise/mesh/gateway/` (resource-server configuration)
- `api-gateway/src/test/java/com/enterprise/mesh/gateway/GatewayEntraProfileTest.java`
- `api-gateway/src/test/java/com/enterprise/mesh/gateway/GatewayFailsClosedWithoutJwtDecoderTest.java`
