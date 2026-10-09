# Architecture

## Request path

```
Internet
   │
   ▼
Application Gateway / ingress-nginx  ── TLS termination, WAF
   │
   ▼
api-gateway  ────────────────────── JWT validation, per-tenant rate limiting,
   │                                 circuit breakers, correlation IDs
   ├──► product-catalog-service   (MongoDB + Redis)
   ├──► review-rating-service     (MongoDB)
   ├──► shopping-cart-service     (Redis)
   ├──► customer-service          (PostgreSQL)
   ├──► ai-orchestrator-service   (MongoDB)
   │
   └──► order-service             (PostgreSQL)
          │
          ├──► inventory-service     reserve stock, confirm, release
          └──► payment-service       authorise, capture, refund

order-service ──events──► Kafka ──► notification-service   (Redis dedupe)
                              ├──► shipping-delivery-service (PostgreSQL)
                              └──► audit trail
```

Only the gateway is reachable from outside the cluster. Everything else is
ClusterIP behind Istio authorization policies.

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

## Availability posture

- `maxUnavailable: 0` on every rollout, so capacity never dips
- PodDisruptionBudget per service, so a node drain cannot empty one
- HPA on CPU and memory with asymmetric scale behaviour
- Zone-level topology spread
- Graceful shutdown (45s) so in-flight checkouts finish and release stock holds
- Kafka consumers on headless Services, so a pod is never load-balanced away
  from the partitions it owns

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
