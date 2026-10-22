# Architecture

## Request path

```
Internet
   │
   ▼
Application Gateway for Containers  ── TLS termination, WAF
   │
   ├── /bff/**, /login ──► bff-service  (session cookie, token bundle in
   │                              Redis, silent refresh, aggregation)
   │                          │
   │                          └──► Microsoft Entra ID  (authorization_code)
   │
   └── everything else ───► api-gateway  ── JWT validation, per-tenant rate
                                │             limiting, circuit breakers,
   │            ┌───────────────┘             correlation IDs
   │            │
   │   (bff-service also calls in here, as any other API client would,
   │    after exchanging the session cookie for a bearer token)
   ▼            ▼
product-catalog-service   (MongoDB + Redis)
review-rating-service     (MongoDB)
shopping-cart-service     (Redis)
customer-service          (PostgreSQL)
ai-orchestrator-service   (MongoDB)
   │
   └──► order-service             (PostgreSQL)
          │
          ├──► inventory-service     reserve stock, confirm, release
          └──► payment-service       authorise, capture, refund

order-service ──events──► Kafka ──► notification-service   (Redis dedupe)
                              ├──► shipping-delivery-service (PostgreSQL)
                              └──► audit trail
```

Only the gateway and the BFF are reachable from outside the cluster, both by
address from Application Gateway for Containers, which has no mesh identity to
authorize by. Everything else is ClusterIP behind Istio authorization policies.

The BFF is **in front of** the gateway rather than behind it. It exists so the
browser never holds an OAuth2 token: the SPA gets an opaque `HttpOnly` session
cookie, the token bundle stays in Redis, and the BFF re-issues a short-lived bearer
token to the gateway on the way through. The gateway stays the single mesh entry
point for every other client. It reaches no business service directly — that would
make it a second edge, and the one path into the mesh that none of the gateway's
controls applied to. See [BFF-SESSION.md](BFF-SESSION.md).

## Why a service mesh here

The mesh earns its cost at the points where hand-rolled client code usually goes
wrong:

- **mTLS everywhere, without certificate plumbing.** Each service gets a verifiable
  identity, which is what makes fine-grained authorization possible.
- **Authorization by service identity.** `shipping-delivery-service` accepts
  webhooks only from the carrier's identity, not from anything inside the
  cluster. Without this, any compromised pod can post a "delivered" status.
- **Outlier detection.** A pod that starts timing out is ejected from the load
  balancer in seconds, instead of failing a share of requests until a human
  notices.
- **Retry policy in one place.** Critically, the retry configuration excludes
  non-idempotent operations. A naive mesh-level retry on `POST /payments` would
  double-charge customers.

The mesh runs in **ambient mode**: a shared `ztunnel` daemon per node provides
Layer 4 to every pod, and a shared `waypoint` provides Layer 7 only for the
services enrolled in it. Nothing is injected into the application, so a mesh
upgrade restarts no application pods and a mesh CVE is patched at the node
layer. See [Istio.md](Istio.md) for which services are enrolled and why.

The north-south edge is **Application Gateway for Containers**, driven through
Gateway API. `ingress-nginx` reached end of life in March 2026, so it is gone;
AGC's data plane runs outside the cluster, which is why the gateway's serving
port is the one place `STRICT` mTLS is relaxed to `PERMISSIVE`. See
[AKS-DEPLOYMENT.md](AKS-DEPLOYMENT.md) §2 and §5.4.

## Data ownership

Each service owns its schema outright. No service reads another's tables, and
there is no shared database. This is what allows independent deploys: order
service can be rolled back without touching payment's migrations.

| Store | Services | Reason |
|---|---|---|
| PostgreSQL | order, payment, inventory, customer, shipping | Transactions, constraints, monetary precision |
| MongoDB | catalogue, review, AI | Variable document shape, read-heavy |
| Redis | cart, catalogue cache, notification dedupe | Ephemeral, low-latency |

Money is `BigDecimal` end to end, with `NUMERIC(19,4)` in PostgreSQL. Prices are
copied onto order lines at checkout so historical invoices never re-derive from a
catalogue that has since changed.

## The checkout saga

Spanning three databases, so no distributed transaction is available or
desirable. The order service orchestrates:

1. **Reserve stock.** Inventory holds units with a TTL. Idempotent on
   `reservationId`.
2. **Authorise payment.** Idempotent on the `Idempotency-Key` header.
3. **Mark paid**, then emit `order.paid` via the outbox.

Compensations, both idempotent:

- Payment declines → release the stock hold.
- Fulfilment fails after authorisation → void the authorisation.

Two properties make retries safe, which matters because Kafka redelivers:

- Every saga step is idempotent on a caller-supplied key.
- Reservations expire on their own. A crashed checkout leaks stock for at most
  the TTL, after which the sweeper reclaims it.

## Event delivery

`common-messaging` configures JSON payloads, `acks=all`, idempotent producer, and
a dead-letter topic per source topic. Consumers use manual acknowledgement, so
offsets advance only after the handler returns.

Events use CloudEvents binary content mode: the Kafka value is the strongly-typed
payload, and metadata rides in headers. This avoids the generic-deserialization
fragility that makes nested envelopes painful in practice, while keeping the
routing metadata that replay and audit need.

Consumers read the raw `Message` rather than a typed DTO. A new field on the
producer side is then ignored rather than causing deserialization failure and
dead-lettering a message that is perfectly readable.

## Multi-tenancy

Tenancy is resolved once, at the gateway, from the validated JWT's `tenant_id`
claim. The gateway **removes** any client-supplied `X-Tenant-ID` before
forwarding, then sets it from the token. Trusting the header alone would let any
caller impersonate another tenant.

Downstream services re-resolve from the JWT as defence in depth, since a
compromised in-cluster workload could bypass the gateway. The tenant is part of
every unique index and every Redis key, so a missing filter cannot leak another
tenant's data even by mistake.

## Security posture

- JWT validated at the edge and again per service
- `STRICT` mTLS; no plaintext anywhere in the mesh
- Deny-by-default `AuthorizationPolicy` plus an egress allow-list
- Default-deny `NetworkPolicy`, with DNS explicitly re-allowed
- Pods run as UID 10001, non-root, read-only root filesystem, all capabilities
  dropped, `RuntimeDefault` seccomp
- Secrets from Key Vault via the CSI driver, never in environment variables
- Payment service never stores a PAN: only an opaque single-use token
- PCI-relevant logging suppressed (Mongo query logs, request bodies)
- OAuth2 tokens never reach browser-reachable storage: the SPA holds an opaque
  session cookie and the token bundle stays in Redis
- The gateway strips `Cookie` from forwarded requests, so a session identifier
  never travels past the edge that issued it
- The BFF keeps CSRF **enabled**, unlike the stateless services: a cookie is
  attached by the browser with no cooperation from the SPA, so without it an
  unauthenticated cross-site form could drive an authenticated checkout
- The BFF's CORS allow-list is enumerated origins, not patterns — a wildcard with
  `allowCredentials(true)` would defeat `SameSite` on the session cookie
- The BFF's authorization rules terminate in `denyAll`, not `authenticated`, so an
  accidental new mapping fails closed
- Per-client-IP rate limiting is enforced at the edge (AGC WAF v2 custom rules),
  before traffic reaches the cluster. See below for why it cannot live at the
  gateway any more

### Why per-IP limiting moved to the edge

The gateway's `ip:` rate-limit bucket is now effectively vestigial for browser
traffic, and worth understanding before anyone "fixes" it by tightening it.

Once `bff-service` sits in front, every anonymous storefront request reaches the
gateway from a BFF pod address. The gateway can no longer see the shopper's IP, so
per-IP bucketing there would pool every anonymous shopper in the world into one
bucket per BFF pod. AGC terminates ahead of the BFF and still holds the real
address, so the WAF's `ClientAddr` grouping is the only per-client-IP control that
is actually per-client-IP.

Grouping deliberately uses `ClientAddr`, the address AGC observed on the socket,
rather than an `X-Forwarded-For` value. An XFF-based grouping would be rotated by
the caller to obtain a fresh counter per request, which would make the rule inert.
AGC's WAF also does not expose the XFF variable in custom rules at all.

The gateway's `tenant:` bucketing is unaffected and still required: it is the only
control on authenticated, per-tenant abuse, and it covers in-mesh callers that never
pass through the edge.

## Availability posture

- `maxUnavailable: 0` on every rollout, so capacity never dips
- PodDisruptionBudget per service, so a node drain cannot empty one
- HPA on CPU and memory with asymmetric scale behaviour
- Zone-level topology spread
- Graceful shutdown (45s) so in-flight checkouts finish and release stock holds
- Kafka consumers on headless Services, so a pod is never load-balanced away
  from the partitions it owns
- BFF sessions live in Redis rather than in sticky Tomcat sessions, because the
  waypoint balances per request and a cookie that only resolved on the pod that
  issued it would log the shopper out whenever they were moved
- BFF readiness deliberately **excludes** Redis: a shopper whose session cannot be
  read can still browse the catalogue, and failing readiness would turn a degraded
  session store into a total storefront outage
- A failing service degrades one section of a BFF aggregation rather than the
  whole page, so one slow dependency cannot blank the storefront

## Deliberate omissions

Stated plainly so nobody mistakes them for oversights:

- **No distributed caching layer (Hazelcast).** It would add a stateful
  dependency for a catalogue that Redis already serves well.
- **No service-registry dependency.** Service discovery comes from the Istio
  registry; Eureka would be redundant infrastructure.
- **No Pessimistic locking for stock.** Optimistic locking with bounded retries
  performs better under the low-contention-per-SKU pattern of a real catalogue.
- **No hand-written mapper layer.** MapStruct-style boilerplate was removed
  because the entity-to-DTO mappings here are small and explicit code is easier
  to review than generated code.
- **No gateway-level rate limiter on `product-catalog` or `ai-orchestrator`.**
  These two routes deliberately carry only a circuit breaker. Per-client-IP
  limiting for their traffic is enforced at the edge instead (see "Why per-IP
  limiting moved to the edge"), which is both cheaper and correct, because the
  gateway cannot see a browser's address behind the BFF. Adding a limiter here as
  well would double-count the same requests against a bucket that no longer
  separates one shopper from another.
