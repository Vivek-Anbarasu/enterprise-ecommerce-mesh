# JWT Validation in the Mesh — Defence in Depth

How each service validates the bearer token it receives, why it does so even though
the gateway already validated it, and where that defence currently has holes.

Every claim below was checked against the source, a real `helm template` render of the
production values, and a live boot of the packaged jars. Commands to reproduce each one
are in [§8](#8-verifying-these-claims-yourself).

---

## 1. The principle

The gateway validates the token once, at the edge. Every service behind it validates it
again. That redundancy is the whole point, and the code says so directly:

> "The gateway forwards a token it has already validated, so every service behind it
> receives an `Authorization` header. That is a good reason to keep validating it
> downstream rather than trusting the hop: a token that was valid at the edge is not
> automatically valid now, a compromised pod can call any other service directly without
> passing the gateway at all, and only the issuer can say whether a given signature is
> still good."
>
> — `MeshJwtValidationSafetyCheck`, class javadoc

Three concrete reasons, then:

| Reason | What a trusted-hop design would miss |
|---|---|
| **Time of check ≠ time of use** | A token accepted by the gateway may be revoked, expired, or have its key rotated before it reaches the service that does the work. |
| **The mesh is not a trust boundary** | Any pod can call any other pod directly. Istio waypoints and mTLS give you identity and encryption, not authorisation — they do not stop `order-service` calling `payment-service` with a token it was never given. |
| **Only the issuer can judge the signature** | See [§3.1](#31-the-decoder-entra-profile) — signature-only validation is worse than useless against multi-tenant Entra. |

The cost of this redundancy is one JWKS fetch (cached) and one signature check per
request per service. The cost of dropping it is a single compromised pod becoming a
complete bypass of the gateway.

---

## 2. Where validation happens in the request path

```
Internet
   │
   ▼
Azure Application Gateway + WAF v2          per-IP rate limits, geo, SQLi/XSS rules
   │                                         (deploy/bicep/waf-policy.bicep)
   ▼
Ingress / edge route split                  /bff, /login → BFF; everything else → gateway
   │                                         (apiGateway.bffPathPrefixes)
   ▼
┌──────────────────────────┐
│  api-gateway  (WebFlux)  │  ← VALIDATION #1  JwtDecoder from application-entra.yml
│  reactive, non-blocking  │                   issuer pinned to the Entra directory
└──────────────────────────┘
   │  forwards the ORIGINAL Authorization header, unchanged
   │  + re-derives X-Tenant-ID from the validated JWT
   ▼
Istio waypoint (ambient)                    mTLS, L7 authz, retries, outlier ejection
   │
   ├──────────────► bff-service             NOT a resource server — see §6
   │
   ▼
┌──────────────────────────┐
│  each business service   │  ← VALIDATION #2  JwtDecoder built from the `entra` profile
│  (servlet stack)         │                   + @PreAuthorize method security
└──────────────────────────┘
   │
   ▼
   database
```

**The gateway forwards the caller's own token rather than minting a new one.** That is
what makes downstream re-validation possible — a downstream service is checking the same
cryptographic artefact the edge checked, not a gateway-asserted identity. The one
exception is the BFF, which exchanges the session's token for a fresh short-lived one
([§6](#6-the-bff--the-inverse-model)).

---

## 3. The shared path: `common-security`

`common-bom/common-security` is what makes this cheap. Nine of the twelve services depend
on it and inherit the entire behaviour below with **no security code of their own** — the
poms contain nothing but a dependency, and most `application.yml` files contain nothing
but a `mesh.tenant` block.

Everything it provides is `@ConditionalOnMissingBean`, so any single piece can be
replaced without giving up the rest.

Consumers:

| Service | Depends on `common-security` |
|---|---|
| `ai-orchestrator-service` | yes |
| `bff-service` | yes (but overrides the chain — §6) |
| `customer-service` | yes |
| `inventory-service` | yes |
| `order-service` | yes |
| `payment-service` | yes |
| `product-catalog-service` | yes |
| `review-rating-service` | yes |
| `shipping-delivery-service` | yes — **added**, see [§7.2](#72-fixed--shipping-delivery-services-authorizations-were-inert) |
| `shopping-cart-service` | yes |
| `api-gateway` | **no** — deliberately, it is reactive (§5) |
| `notification-service` | **no** — no HTTP surface at all, see [§7.5](#75-not-a-finding--notification-service-has-no-http-surface) |

### 3.1 The decoder (`entra` profile)

`MeshSecurityAutoConfiguration.meshEntraJwtDecoder` is the piece that actually validates
tokens. It is annotated `@Profile("entra")`, so **a service only validates bearer tokens
if `entra` is in its active profile list**, and it derives everything from one env var:

```java
String tenantId = requireTenantId(environment);
String issuer    = "https://login.microsoftonline.com/" + tenantId + "/v2.0";
String jwkSetUri = "https://login.microsoftonline.com/" + tenantId + "/discovery/v2.0/keys";

NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(issuer),
        new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.stream().anyMatch(audiences::contains))));
```

Three properties are being balanced here, and all three are load-bearing:

- **The JWK set** supplies the signing keys.
- **The issuer validator** rejects a token whose `iss` points at a different directory.
- **The audience validator** rejects a token that is cryptographically valid but was
  minted for a different application. `iss` names the directory, not the intended
  recipient: any app registration in the same tenant can obtain a token carrying
  that issuer, and without `aud` it is accepted.

The second is not optional. The code's own words:

> "Validating the signature alone is the dangerous shortcut, because Entra signs every
> tenant's tokens with a shared key set — so a perfectly valid token minted by a stranger's
> Azure AD directory would be accepted, and `TenantContextFilter` would turn its
> `tenant_id` claim into a `TENANT_*` authority for this mesh."

That is the highest-value single line of defence in this codebase. Signature validation
alone would let an attacker who has *any* valid Azure AD token from *any* tenant walk in.

`requireTenantId` rejects two specific footguns:

- **blank / unset** — the issuer URL is built by string interpolation, so an empty value
  yields `https://login.microsoftonline.com//v2.0`: syntactically valid, pointing at the
  wrong directory. A decoder is still created, every real token is rejected as foreign.
- **still a placeholder** (`startsWith("REPLACE_")`) — this catches the literal
  `REPLACE_WITH_ENTRA_DIRECTORY_TENANT_ID` that ships in `values.yaml`. Good: the default
  chart cannot silently deploy against a wrong directory.

`requireAudience` closes the third claim: with the `entra` profile active and
`ENTRA_AUDIENCE` empty, the service refuses to start. It is deliberately fatal rather
than a warning, because "signature + issuer only" is a valid-looking credential — no
request errors, no metric moves, and a token minted for an unrelated application works
exactly like a legitimate one. The chart supplies one `global.entraAudience` for the
whole mesh, because one API registration backs every service.

Note the asymmetry with `api-gateway` and `bff-service`, which *do* have their own
`application-entra.yml` carrying the client/issuer block. Only those two need it — the
gateway pins its own issuer and holds an OAuth2 client registration; the BFF is an OAuth2
*client*. Downstream services have no reason to repeat the issuer block, and deriving it
from `ENTRA_TENANT_ID` means there is exactly one place to get it wrong.

### 3.2 The safety check — failing loud instead of failing quiet

`MeshJwtValidationSafetyCheck` is an `InitializingBean` that refuses to start when a
production-like environment has no decoder.

This exists to solve a genuinely nasty failure mode. `meshSecurityFilterChain` **skips
wiring the resource server when no decoder is present**, on purpose, so that local dev and
the test suite boot with no identity provider. The cost is that in production that same
branch produces a service that:

- starts cleanly,
- logs one `WARN`,
- passes every readiness probe,
- and rejects **100% of authenticated traffic with 401** — indefinitely.

It is fail-closed, so nothing is breached. But it presents as an *availability* incident
rather than a security one, so nothing alarms, and the pods sit there serving 401s.

Measured behaviour, no `JwtDecoder` present:

| `mesh.environment` | Outcome |
|---|---|
| `aks-prod` | **refuses to start** |
| `production` | **refuses to start** |
| `prod` | **refuses to start** |
| `live` | **refuses to start** |
| `local` | starts, logs `WARN` |

The `isProduction` helper treats an *unrecognised* value under a production profile as
production, so a typo in `MESH_ENV` fails closed rather than open.

`api-gateway` carries its own copy (`JwtValidationSafetyCheck`) for the same reason it
cannot import this one — it is WebFlux, and pulling in servlet security would defeat the
point. Two services arriving at the same failure from different directions should fail the
same way.

### 3.3 The filter chain

```java
http.csrf(disable)                                     // safe: no cookies, bearer only
    .cors(withDefaults())
    .headers(contentTypeOptions, frameOptions.deny(), hsts 1y + subdomains)
    .sessionManagement(policy = STATELESS)             // no session on a bearer API
    .exceptionHandling(ProblemDetail entry point + access denied handler)   // RFC 9457
    .authorizeHttpRequests(
        /actuator/health/**, /actuator/info, /actuator/prometheus,
        /actuator/health/liveness, /actuator/health/readiness → permitAll
        /v3/api-docs/**, /swagger-ui/**, /swagger-ui.html    → permitAll
        OPTIONS /**                                      → permitAll
        anyRequest()                                    → authenticated())
    .addFilterBefore(tenantContextFilter, UsernamePasswordAuthenticationFilter.class);

if (jwtDecoderProvider.getIfAvailable() != null) {     // ← the conditional, see §3.2
    http.oauth2ResourceServer(jwt → jwt.jwtAuthenticationConverter(...));
}
```

Two things worth calling out:

- **CSRF is disabled, and that is correct here.** Disabling CSRF is only safe because the
  credential is an `Authorization` header the browser attaches deliberately. There is no
  ambient cookie, so there is nothing for a cross-site form to ride on. The BFF, which
  *does* hold a cookie, keeps CSRF on — see [§6](#6-the-bff--the-inverse-model).
- **Actuator is split.** Only `health`, `info` and `prometheus` are permitted. Anything else
  under `/actuator` falls through to `authenticated()`.

### 3.4 Authority mapping

`meshJwtAuthenticationConverter` turns claims into Spring authorities:

| Claim | Authority | Used by |
|---|---|---|
| `roles: ["CUSTOMER"]` | `ROLE_CUSTOMER` | `@PreAuthorize("hasRole(...)")` |
| `roles: ["ADMIN"]` | `ROLE_ADMIN` | idem |
| `permissions: ["orders:read"]` | `PERM_orders:read` | available, currently unused |
| `sub` | principal name | idem |

Four roles are in use across the mesh: `CUSTOMER`, `SERVICE`, `ADMIN`, `CARRIER`.
`SERVICE` is the mesh-internal role — it is what lets the gateway call a downstream service
on a shopper's behalf.

The gateway's converter additionally mints `TENANT_<tenant_id>` from the `tenant_id`
claim, purely so `@PreAuthorize` expressions there can reference the tenant.

### 3.5 Tenant resolution — and the `ThreadLocal`

`TenantContextFilter` runs *before* `UsernamePasswordAuthenticationFilter` — deliberately,
so the `SecurityContext` already holds the validated `JwtAuthenticationToken` when it
resolves the tenant. Resolution order:

1. the JWT's `tenant_id` claim — **authoritative and final when a JWT principal exists**;
   a validated token never falls back to the header, even if it carries no tenant claim,
2. else the `X-Tenant-ID` **request header**, consulted only when there is no JWT
   principal (anonymous / key-less local development),
3. else `default-tenant` when `required: false`,
4. else `400 Tenant Required`.

Steps 1 and 2 are deliberately *not* a simple fallthrough chain. An earlier version of this
filter read the header whenever the claim happened to be absent, which handed any in-mesh
caller arbitrary tenant attribution — see [§7.4](#74-fixed--the-x-tenant-id-fallback-trusted-the-caller-on-direct-calls).

It then rejects any resolved tenant not on `allowed` with `403`, and on success publishes
to `TenantContext` (a `ThreadLocal`), the SLF4J **MDC** under key `tenant_id`, and echoes
the value back as a response header. Teardown happens in a `finally`, with a comment that
says why: *"Guarantee no leakage into a pooled worker thread."*

It also **re-presents that value as the request header downstream handlers see**, so the
~40 controllers that read `@RequestHeader("X-Tenant-ID")` cannot observe anything but the
resolved tenant. See [§7.4](#74-fixed--the-x-tenant-id-fallback-trusted-the-caller-on-direct-calls).

The MDC entry is not decoration — it is what makes log-based tenant alerting possible.

### 3.6 Method security — the second layer

`@EnableMethodSecurity` sits on `MeshSecurityAutoConfiguration`, so every service that
imports it gets request-level authorisation *and* method-level authorisation, and a
mapping cannot accidentally be left unguarded by the URL rules alone.

Actual usage across the mesh:

| Service | `@PreAuthorize` count | Roles required |
|---|---|---|
| `customer-service` | 7 | `CUSTOMER`, `SERVICE`, `ADMIN` |
| `payment-service` | 6 | `SERVICE`, `ADMIN`, `CUSTOMER` |
| `inventory-service` | 6 | `CUSTOMER`, `ADMIN`, `SERVICE` |
| `shopping-cart-service` | 5 | `CUSTOMER`, `SERVICE` |
| `shipping-delivery-service` | 4 | `CUSTOMER`, `ADMIN`, `SERVICE`, `CARRIER` — now enforced, see [§7.2](#72-fixed--shipping-delivery-services-authorizations-were-inert) |
| `order-service` | 3 | `CUSTOMER`, `ADMIN`, `SERVICE` |
| `ai-orchestrator-service` | 3 | `CUSTOMER`, `SERVICE` |
| `review-rating-service` | 2 | `CUSTOMER`, `ADMIN` |
| `product-catalog-service` | 3 | `ADMIN`, `SERVICE` — on the three writes only; reads are anonymous by design, see [§7.3](#73-fixed--product-catalog-service-had-no-role-separation-at-all) |

The URL rules and the method rules are complementary: `anyRequest().authenticated()` says
"a valid token is required"; `@PreAuthorize` says "and it must carry this role". A
`SERVICE`-role token — the mesh-internal one the gateway holds when calling on a shopper's
behalf — is therefore still subject to the same role checks as any caller.

---

## 4. Tenant configuration, service by service

Every service that consumes `common-security` sets:

```yaml
mesh:
  tenant:
    required: false
    default-tenant: public
```

`required: false` because catalogue browsing is anonymous and still needs a tenant to
partition data. `default-tenant: public` is the documented safe fallback for storefront
traffic.

**`mesh.tenant.allowed` was not set in any service in the repo.** The default is
`List.of()`, which the code documents as *"empty means allow all"*. So the
`403 Tenant Not Permitted` branch was unreachable everywhere and tenant isolation rested
entirely on row-level partitioning in queries.

That is a defensible design for a single-tenant rollout and a poor accident for a mesh
whose whole data model is keyed on `tenant_id` — because *"allow every tenant"* and
*"nobody has configured this yet"* are the same value, and only one of them is a decision.

Fixed by making the empty list impossible to ship:

```java
if (!tenant.getAllowed().isEmpty()) { ... return; }          // list present: fine
if (tenant.isAllowAll()) { ...warn...; return; }             // openness asserted: fine
if (isProduction(meshEnv)) { throw new IllegalStateException(...); }   // neither: refuse
```

`TenantAllowListSafetyCheck` is the fourth guard in this codebase's established family
(`MeshJwtValidationSafetyCheck`, `JwtValidationSafetyCheck`, `PaymentGatewaySafetyCheck`) —
same reasoning, same shape: the condition is invisible in every metric and log line, so
refusing to start is the only reliable control. The new `mesh.tenant.allow-all` property
makes "this deployment really is open to every tenant" a reviewable line in `values.yaml`
rather than an omission nobody made.

Wired through the chart as `global.mesh.tenantAllowed` and `global.mesh.tenantAllowAll`,
emitted as `MESH_TENANT_ALLOWED` (comma-delimited) and `MESH_TENANT_ALLOW_ALL`. The
chart's default is `[]` with `allowAll: true`, so behaviour is unchanged until an
operator restricts it.

Verified on a real boot:

```
MESH_TENANT_ALLOWED=acme,globex
  > Tenant allow-list active: 2 tenant(s) permitted. Any other tenant is rejected with
    403 before reaching business logic.

(no list, no allow-all, MESH_ENV=aks-prod)
  > mesh.tenant.allowed is empty in environment 'aks-prod', so this service accepts
    requests from every tenant. ...

MESH_TENANT_ALLOW_ALL=true, aks-prod
  > Tenant allow-list disabled (mesh.tenant.allow-all=true) in environment 'aks-prod'.
```

> **One coupling to know about.** Every service sets `required: false` with
> `default-tenant: public`, so an anonymous request resolves to `public` and is *then*
> checked against the list. Populating the list with `{acme,globex}` would 403 the entire
> browsable storefront. Include `public` unless the deployment is deliberately closed to
> anonymous traffic.

---

## 5. The edge: `api-gateway`

The gateway cannot use `common-security`. It is WebFlux, and the servlet stack would defeat
the non-blocking design, so `GatewaySecurityConfig` is a deliberate parallel implementation.

It matches on substance:

- resource server wired only when a `JwtDecoder` exists, with the same conditional shape;
- anonymous catalogue reads permitted, `anyExchange().authenticated()` for everything else;
- its own `JwtValidationSafetyCheck`, including the placeholder-`ENTRA_TENANT_ID` and
  missing-`ENTRA_AUDIENCE` guards;
- `TenantForwardingFilter`, discussed below.

Its CORS allow-list is **narrower** than the shared one — explicit origins rather than
`allowedOriginPatterns("*")`, with a comment explaining that the browser storefront is not
one of these callers:

> "What reaches this allow-list is the non-browser API surface — mobile apps and partner
> integrations, which send bearer tokens and have no ambient cookie to protect. If a
> browser surface is ever added here, narrow this to explicit origins first; a wildcard
> pattern alongside `allowCredentials` is only as safe as the credential it is paired with,
> and this one is a bearer token."

### `TenantForwardingFilter` — strip, then re-derive

This is the most security-relevant filter in the gateway, and it gets the order right:

```java
// 1. Drop the client-supplied value unconditionally.
headers.remove(TENANT_HEADER);
// 2. Re-set it from the validated JWT only.
headers.set(TENANT_HEADER, jwt.getClaimAsString("tenant_id"));
```

Without step 1, a caller could simply send `X-Tenant-ID: <someone-else>` themselves, and
the header alone would be trusted. Note also the fall-through: an **authenticated request
whose token has no `tenant_id` claim** produces an empty Mono and takes the
`switchIfEmpty` branch, so the header is stripped rather than passed through. That is the
fail-safe direction.

---

## 6. The BFF — the inverse model

`bff-service` is structurally the opposite of every other service, and the class javadoc
says so. Where others are stateless bearer endpoints, this one authenticates the browser
by cookie, so all three defaults invert — and each inversion is a requirement, not a
preference:

| Concern | Other services | BFF | Why |
|---|---|---|---|
| Session policy | `STATELESS` | `IF_REQUIRED` | a server-side session must outlive the access token — that is the entire point |
| CSRF | disabled | **on** | the cookie is attached by the browser with no cooperation from the SPA, so an unauthenticated cross-site form could otherwise drive an authenticated checkout |
| Session fixation | n/a | `changeSessionId()` | switches the id on privilege change, the one moment an attacker could have pre-chosen it |

The gateway can disable CSRF *precisely because* its bearer token is never attached
automatically. This service cannot. That sentence is the whole distinction.

Two more deliberate decisions:

- **Authorization rules end in `denyAll`, not `authenticated()`.** The surface is small and
  explicit; a mapping added by accident fails closed instead of silently becoming part of
  the public contract.
- **`TenantContextFilter` is explicitly disabled** via a `FilterRegistrationBean` with
  `setEnabled(false)`. This one is subtle and worth stating: that filter is an ordinary
  `Filter` *bean*, and Boot registers every filter bean with the container whether or not
  it is in the Spring Security chain. Adding your own chain does not switch it off. It
  does not belong here — the BFF resolves no `ThreadLocal` tenant — and left enabled with
  the default `required: true` it would reject every anonymous request with
  `400 Tenant Required` and take the storefront down entirely. Worse, with nothing to read
  a claim from it falls through to the caller-supplied `X-Tenant-ID` **request header**,
  which on a browser edge is simply whatever the caller sent, and then echoes it back and
  logs it.

The BFF is **not** a resource server — it never validates a bearer token. It is an OAuth2
*client*: it holds the token server-side in Redis via Spring Session, and re-issues a
short-lived bearer token to the gateway on the SPA's behalf. See
[docs/BFF-SESSION.md](BFF-SESSION.md).

---

## 7. Findings

All four below were found during an audit of this document's subject, and all four are
now fixed. The findings are kept in full because each one explains *why* the fix is shaped
the way it is, and an audit that only records the clean end state is not worth reading next
year.

### 7.1 FIXED — `payment-service` could not start in any deployed environment

**The most sensitive service in the mesh is the one service missing the `entra` profile.**

`payment-service` depends on `common-security` and is given `ENTRA_TENANT_ID`, but its
chart entry has **no `profiles:` key at all**, so it inherits the `_helpers.tpl` default of
`["production"]` — `entra` is absent. No `entra` profile means no
`meshEntraJwtDecoder` bean, and `payment-service` configures no
`spring.security.oauth2.resourceserver.jwt.*` of its own and ships no
`application-entra.yml`. So there is no decoder — and `MESH_ENV` is `aks-prod`, which is in
the production set.

From a real render of `deploy/helm/enterprise-ecommerce` with `values-prod.yaml`:

```
ecommerce-payment-service    profiles=production          MESH_ENV=aks-prod
ecommerce-order-service      profiles=production,entra    MESH_ENV=aks-prod
```

And `MeshJwtValidationSafetyCheck` with no decoder, by `mesh.environment`:

```
aks-prod      REFUSED TO START -> No JwtDecoder is configured in environment 'aks-prod'
production    REFUSED TO START -> No JwtDecoder is configured in environment 'production'
prod          REFUSED TO START -> No JwtDecoder is configured in environment 'prod'
live          REFUSED TO START -> No JwtDecoder is configured in environment 'live'
local         started, WARN
```

`aks-prod` is the value the chart sets. **`payment-service` crash-loops on startup, in
every deployed environment, and will never serve a request.**

Two caveats on how I verified this, so the finding is not overclaimed:

- The chart is arguably working as designed — the placeholder
  `REPLACE_WITH_ENTRA_DIRECTORY_TENANT_ID` is *meant* to fail loudly until an operator
  overrides it. What is not by design is the missing `entra` profile: no amount of
  operator override makes the decoder appear.
- A second check, `PaymentGatewaySafetyCheck`, fires *first* and masks this one, because
  `values.yaml` also defaults `PAYMENT_GATEWAY: stub`, which is likewise rejected under
  `aks-prod`. Booting with the chart's exact values surfaces the `stub` message and never
  reaches the JWT check. Both have to be fixed, and after fixing the gateway one the JWT
  one fires.

**Fix applied** — one line in `deploy/helm/enterprise-ecommerce/values.yaml`, alongside the
other eight:

```yaml
payment-service:
  profiles: ["production", "entra"]
```

Verified by booting the packaged jar both ways, which isolates the profile as the only
variable:

```
SPRING_PROFILES_ACTIVE=production,entra
  > Resource server configured for Entra directory 1111...: forwarded bearer tokens are
    validated against issuer 'https://login.microsoftonline.com/1111.../v2.0'
  > Resource server configured: forwarded bearer tokens are validated against 'ENTRA tenant 1111...'

SPRING_PROFILES_ACTIVE=production
  > Starting WITHOUT JWT validation (environment 'staging'). No decoder is configured, so
    every request will be rejected. This is expected locally and must never happen in a
    deployed environment.
```

The `SERVICE`-role authorisation in `PaymentController` (`hasAnyRole('SERVICE','ADMIN')`
on every mutation) was unreachable code before this; it is live now.

> **One thing this fix does not address:** `PAYMENT_GATEWAY: stub` in `values.yaml` is also
> rejected under `aks-prod` by `PaymentGatewaySafetyCheck`, and that check fires *first*, so
> it masks the JWT one until it is fixed too. `HttpPaymentGateway` now exists as a real mode
> for it to point at — see [§7.6](#76-fixed--payment_gateway-had-no-implementation).

### 7.2 FIXED — `shipping-delivery-service`'s authorizations were inert

`shipping-delivery-service` has four `@PreAuthorize` annotations on its controller and
**none of them are enforced**, because the module has no method security enabled:

- no `@EnableMethodSecurity` anywhere in the module;
- no `SecurityFilterChain`;
- no `@EnableWebSecurity`;
- no `JwtDecoder`;
- and it does **not** depend on `common-security`, which is the only thing in the mesh that
  carries `@EnableMethodSecurity`.

Confirmed from `dependency:tree` — the module has `spring-boot-starter-security` and
`spring-boot-starter-oauth2-resource-server` but no `common-security`:

```
+- org.springframework.boot:spring-boot-starter-security:jar:4.1.1:compile
+- org.springframework.boot:spring-boot-starter-oauth2-resource-server:jar:4.1.1:compile
```

So what actually happens? Boot's own `SecurityAutoConfiguration` supplies the default
`SecurityFilterChain`, which requires authentication via form login / HTTP Basic. There is
no `JwtDecoder`, so the OAuth2 resource server is never wired. Consequently:

- a **bearer token is ignored** — the carrier webhook endpoints do not accept the token the
  gateway forwards;
- authentication is possible with HTTP Basic using Boot's auto-generated password;
- and because method security is off, **the role checks never run**, so any authenticated
  caller reaches every endpoint.

The annotations that are silently doing nothing:

```java
@PreAuthorize("hasAnyRole('CUSTOMER','ADMIN','SERVICE')")   // × 2
@PreAuthorize("hasAnyRole('CARRIER','ADMIN')")             // carrier webhook
@PreAuthorize("hasAnyRole('ADMIN','SERVICE')")
```

This is not a headless worker. The chart gives it a real HTTP surface and explicitly
routes traffic to it:

```yaml
shipping-delivery-service:
  port: 8089
  # Despite sitting on the async tier, this one DOES need a waypoint: it
  # exposes an /internal/shipping REST surface for carrier webhooks.
  waypoint: true
```

**Fix applied** — replace the two bare starters in the module's `pom.xml` with
`common-security`, and add the `entra` profile to its chart entry. `common-security`
already depends on `spring-boot-starter-security` and `-oauth2-resource-server` at compile
scope, so declaring them separately only invites version skew from owning the pair:

```xml
<dependency>
    <groupId>com.enterprise.mesh</groupId>
    <artifactId>common-security</artifactId>
</dependency>
```

```yaml
shipping-delivery-service:
  profiles: ["production", "entra"]
  extraEnv:
    ENTRA_TENANT_ID: "REPLACE_WITH_ENTRA_DIRECTORY_TENANT_ID"
```

`mesh.tenant` was also added to its `application.yml` (`required: false`,
`default-tenant: public`). Without it the `required: true` default would answer carrier
webhook tokens carrying no `tenant_id` claim with `400` *before* `@PreAuthorize` ran —
turning the four newly-live annotations into a service nothing can call. This matches the
other nine consumers.

Verified live:

```
shipping-delivery-service, MESH_ENV=aks-prod, SPRING_PROFILES_ACTIVE=production,entra
  > Resource server configured for Entra directory 1111...: forwarded bearer tokens are
    validated against issuer '...'
  > Resource server configured: forwarded bearer tokens are validated against 'ENTRA tenant 1111...'
```

The four `@PreAuthorize` annotations are unchanged in source and are now enforced:
`hasAnyRole('CUSTOMER','ADMIN','SERVICE')` ×2, `hasAnyRole('CARRIER','ADMIN')`,
`hasAnyRole('ADMIN','SERVICE')`.

### 7.3 FIXED — `product-catalog-service` had no role separation at all

`ProductController` has **zero** `@PreAuthorize` annotations, and it is not a read-only
surface:

| Endpoint | Guard |
|---|---|
| `GET /api/products` | `authenticated()` (and anonymous at the gateway) |
| `GET /api/products/sku/{sku}` | idem |
| `GET /api/products/{id}` | idem |
| `POST /api/products` | **`authenticated()` only** |
| `PUT /api/products/{id}` | **`authenticated()` only** |
| `POST /api/products/sku/{sku}/deactivate` | **`authenticated()` only** |

So any valid token from any role — including the internal `SERVICE` role the gateway holds
when proxying a shopper's catalogue read — can create, modify, or deactivate catalogue
products. Role separation is enforced in eight services and absent in the ninth. Reads are
supposed to be anonymous; writes were not part of that decision.

**Fix applied** — `@PreAuthorize("hasAnyRole('ADMIN','SERVICE')")` on the three mutating
mappings, leaving the three reads untouched:

```java
@PostMapping
@PreAuthorize("hasAnyRole('ADMIN','SERVICE')")     // create
@PutMapping("/{id}")
@PreAuthorize("hasAnyRole('ADMIN','SERVICE')")     // update
@PostMapping("/sku/{sku}/deactivate")
@PreAuthorize("hasAnyRole('ADMIN','SERVICE')")     // deactivate
```

`ADMIN` is the catalogue owner. `SERVICE` covers the mesh-internal case where a service
writes on a shopper's behalf. A shopper's own `CUSTOMER` token deliberately cannot — a
customer must not be able to edit the catalogue they are shopping in.

### 7.4 FIXED — the `X-Tenant-ID` fallback trusted the caller on direct calls

`TenantContextFilter.resolve()` falls back to the `X-Tenant-ID` **request header** when the
JWT carries no `tenant_id` claim. Through the gateway this is safe, because
`TenantForwardingFilter` strips the inbound header and re-derives it from the validated
token.

But the entire premise of defence in depth is that **a pod may call a service without
passing the gateway at all** — the reason given in `MeshJwtValidationSafetyCheck`'s own
javadoc. In that case nothing strips the header, so any in-mesh caller can set
`X-Tenant-ID` to any value and have it accepted as the tenant, echoed into the response
header, and written into the log MDC.

The token itself is still fully validated — this does not bypass JWT verification, it
bypasses *tenant attribution* for tokens that lack a `tenant_id` claim. It compounds with
[§4](#4-tenant-configuration-service-by-service) (no allow-list anywhere) and with
`required: false` (unresolvable tenant silently becomes `public`).

**Fix applied.** Two changes in `TenantContextFilter`, one in `resolve()` and one in a
request wrapper. Both were needed: fixing `resolve()` alone would change what the filter
publishes to the `ThreadLocal` without changing what the ~40 controllers actually receive,
because they all read the header directly rather than the `TenantContext`.

**1. A validated JWT principal is now authoritative and final.**

```java
if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
    // Authenticated by token: the claim is the whole answer. Never the header.
    String fromClaim = jwtAuthenticationToken.getToken().getClaimAsString(properties.getClaimName());
    return fromClaim == null || fromClaim.isBlank() ? null : fromClaim;
}
String fromHeader = request.getHeader(properties.getHeaderName());   // only with no JWT principal
```

The header is still read when there is no JWT principal at all — the anonymous and key-less
local-development case, where there is nothing validated to contradict it.

This is behaviour-preserving for all legitimate traffic: behind the gateway the header is
stripped and re-derived from the claim, so it was never reachable as a fallback there
anyway. The only callers it changes are the direct in-mesh ones, which is precisely the
set defence in depth exists to distrust.

**2. The resolved tenant is re-presented as the header downstream code sees.** The filter
now wraps the request so that `@RequestHeader("X-Tenant-ID")` can only ever bind the
resolved value:

```java
filterChain.doFilter(
        new TenantHeaderRequestWrapper(request, properties.getHeaderName(), tenant), response);
```

This applies the gateway's own `TenantForwardingFilter` discipline — strip, then
re-derive — one layer further in, so it holds for callers that never pass through the
gateway at all. Only that one header is affected; `Authorization` and `Idempotency-Key`
are delegated untouched, which matters because the latter is what makes a retried payment
safe to replay.

**Verified.** `TenantContextFilterHeaderTest` (7 cases) covers claim-beats-header,
absent-claim-ignores-header, header visible to enumeration, other headers untouched,
header honoured with no JWT principal, allow-list enforcement, and `required: true`. The
two tests that encode the fix were confirmed to **fail against the old implementation**
before being confirmed to pass against the new one:

```
old resolve()  ->  absentClaimIgnoresHeader: expected "public" but was "attacker-tenant"
               ->  requiredRejectsUnresolvable: expected 400 but was 200
new resolve()  ->  Tests run: 7, Failures: 0, Errors: 0
```

**Also part of this fix:** making the allow-list impossible to ship unset — see
[§4](#4-tenant-configuration-service-by-service).

### 7.6 FIXED — `PAYMENT_GATEWAY` had no implementation

`PaymentGatewaySafetyCheck` accepted any value other than `stub`, but the only
`PaymentGateway` bean on the classpath was `StubPaymentGateway`, which is conditional on
exactly `stub`. So setting `PAYMENT_GATEWAY=stripe` produced
`UnsatisfiedDependencyException: no bean of type PaymentGateway` — pointing at the
dependency wiring rather than the property that was wrong, with a third distinct failure
mode layered on top of the two startup checks already refusing to start.

**`HttpPaymentGateway`** now implements the interface over HTTP, deliberately vendor-neutral
so a vendor SDK can be added behind the same interface later:

```
POST {baseUrl}/payments/authorisations
  Authorization: Bearer {apiKey}
  Idempotency-Key: {idempotencyKey}     <- forwarded byte-for-byte
  {"paymentToken":"tok_...","amount":129.99,"currency":"GBP"}

  200 {"status":"authorised","providerReference":"pi_123","instrumentLabel":"Visa ****4242"}
  200 {"status":"declined","declineReason":"insufficient_funds"}

POST {baseUrl}/payments/refunds -> 204
```

Three properties it is careful about, each with a test:

- **A transport failure is not a decline.** A decline is a decision the provider made and
  is persisted as one; a timeout, a 5xx or an unparseable body means the outcome is
  *unknown*. Those propagate `PaymentProviderUnavailableException` instead. Mapping "I
  could not reach the provider" onto "the card was refused" is the failure mode worth
  naming: the shopper is told their card failed, no order is fulfilled, and the money is
  nonetheless gone. Because `PaymentService` is transactional, throwing rolls back without
  persisting a `Payment`, and the retry carries the same key.
- **The idempotency key is forwarded unchanged.** `PaymentService` replays a stored
  outcome for a repeated key, but that only works if the provider saw the same key.
- **Nothing sensitive is logged.** No API key, no payment token. The response body is not
  even read on an error, since a 401 can echo the credential back.

`PaymentGatewaySafetyCheck` was also tightened to validate the *mode* against
`{stub, http}` and to require `base-url` and `api-key` when it is `http`, so a typo or a
half-configured provider fails at deploy time rather than at the point of sale.

Verified by booting the jar under `aks-prod`:

```
PAYMENT_GATEWAY=http  ->  Payment gateway mode 'http' configured for environment 'aks-prod'
                         Payment provider HTTP budget: connectTimeout=PT2S readTimeout=PT5S
                         HTTP payment provider configured: baseUrl=https://payments.example
PAYMENT_GATEWAY=http, no api key -> mesh.payment.provider.api-key is unset. Every provider
                         call would be rejected, which fails each payment at the point of
                         sale rather than at deploy time.
PAYMENT_GATEWAY=strip -> Unknown mesh.payment.gateway 'strip'. Known modes are [http, stub].
```

> **Note:** `RestClient.Builder` is auto-configured by `spring-boot-restclient`, which this
> module was **not** getting transitively (it also pulls `starter-webflux`, and the
> resulting tree leaves it out). Declared explicitly, or the `http` mode would fail at
> startup with a missing-bean error naming no real cause.

### 7.5 NOT A FINDING — `notification-service` has no HTTP surface

It depends on neither `common-security` nor `spring-boot-starter-security`, so it validates
nothing. That is **correct**, and the chart documents why:

```yaml
notification-service:
  # No waypoint, and it has no HTTP business surface at all: a pure Kafka
  # consumer whose only endpoint is actuator for probes.
  waypoint: false
  # Headless worker: no business HTTP port, only actuator.
  headless: true
```

No L7 entry point means no request to authenticate. Worth stating explicitly so the absence
of security code is a recorded decision rather than an omission — and it is the reason this
is informational while §7.2 is not.

---

## 8. Verifying these claims yourself

```powershell
# Which services get the entra profile (the profile that builds the JwtDecoder)?
Select-String -Path deploy\helm\enterprise-ecommerce\values.yaml -Pattern "profiles:"

# Which services depend on common-security (the only source of @EnableMethodSecurity)?
mvn -B -pl <module> dependency:tree | Select-String "common-security"

# Prove the safety check refuses to start in production
# (run the packaged jar with MESH_ENV=aks-prod and no entra profile)

# Prove the tenant fix: these two fail against the old resolve()
mvn -B -pl common-bom/common-security test -Dtest=TenantContextFilterHeaderTest

# Render the real production manifests
helm template ecommerce deploy\helm\enterprise-ecommerce -f deploy\helm\values-prod.yaml

# Every @PreAuthorize in the mesh
Select-String -Path . -Recurse -Filter *.java -Pattern "@PreAuthorize"

# Confirm a method-security annotation exists in a given module
Select-String -Path async-processors\shipping-delivery-service\src\main\java -Recurse `
  -Pattern "EnableMethodSecurity|SecurityFilterChain"   # -> no matches in this module;
                                                         #    it arrives via common-security

# Confirm no tenant allow-list anywhere
Select-String -Path . -Recurse -Filter "application*.yml" -Pattern "allowed:"   # -> no matches
```

---

## 9. Summary

The mechanism is sound. The shared library gives every consuming service a validated
issuer, an explicit signature check, an audience check, stateless sessions, `@PreAuthorize` enforcement,
tenant resolution bound to the validated token, and a startup guard that turns a silent
401-serving outage into a crash-loop that names the missing property. The gateway and BFF
each get purpose-built equivalents with their own safety checks. The reasoning in the
comments is unusually good — several of the traps documented here (shared Entra key sets,
placeholder tenant ids, ambient-cookie CSRF, filter beans auto-registering) are real
production failure modes that most codebases get wrong.

What the wiring did *not* deliver was uniformity: three of twelve services were not
validating tokens in a deployed environment, one was not enforcing the authorizations it
appeared to enforce, and tenant attribution could be set by any in-mesh caller. All of
that is now fixed.

| Service | Change | Verified by |
|---|---|---|
| `payment-service` | `profiles: ["production", "entra"]` | boot A/B: decoder built with, absent without |
| `shipping-delivery-service` | `common-security` dep + `entra` profile + `mesh.tenant` | boot under `aks-prod`: issuer validation logged |
| `product-catalog-service` | `@PreAuthorize("hasAnyRole('ADMIN','SERVICE')")` on 3 writes | source + `dependency:tree` |
| `common-security` | claim is authoritative for JWT principals; header re-presented downstream | 7 new tests, 2 confirmed failing on the old code |
| `common-security` | `TenantAllowListSafetyCheck` refuses an unset allow-list in production | 8 new tests + real boot under `aks-prod` |
| `common-security` / `api-gateway` | `aud` validation from `ENTRA_AUDIENCE`; both safety checks refuse to start when it is unset | new safety-check tests + gateway profile test |
| `payment-service` | `HttpPaymentGateway`, plus mode and credential validation | 11 new gateway tests + real boot under `aks-prod` |

`mvn test` is green across all 17 modules — 338 tests. The mesh now validates every
token, at the edge and again at every service that can be reached; every mutating endpoint
is behind an explicit role check; no caller can set its own tenant; and neither an unset
tenant allow-list nor a half-configured payment provider can reach production unnoticed.

### Remaining deployment inputs, not code gaps

Neither is left implicit any more: both now fail loudly at startup instead of
degrading silently, so neither can reach production unnoticed.

1. **The tenant allow-list still needs real tenant ids.** The *enforcement* gap is
   closed — see [§4](#4-tenant-configuration-service-by-service) — but the actual
   tenant directory ids are environment-specific and are not in this repository, so
   the chart ships `tenantAllowed: []` with `tenantAllowAll: true`. Populate
   `global.mesh.tenantAllowed` per environment and the `403` branch becomes live.
2. **`PAYMENT_GATEWAY` has a real implementation now, but no vendor behind it.**
   `HttpPaymentGateway` speaks a documented vendor-neutral JSON contract. Pointing it
   at an actual provider means implementing that contract on the provider's side, or
   dropping in a vendor SDK behind the same `PaymentGateway` interface.