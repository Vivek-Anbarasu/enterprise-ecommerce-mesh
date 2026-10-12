# High Level Design

Architecture decisions for the enterprise e-commerce mesh. Operational detail
lives in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); this document records
*why*.

## 1. Service decomposition

Eleven services, split along business capability rather than technical layer.
Each owns its data outright and can be deployed independently.

Rejected alternative: a single modular monolith. It would have been faster to
build, but the brief calls for independent scaling of browse traffic versus
checkout traffic, which are exactly the two profiles that pull a shared
deployment in opposite directions.

Rejected alternative: shared-database services. Independent deploys become
impossible once two services migrate the same schema, and that coupling is
usually discovered during an incident.

## 2. Synchronous calls vs events

**Synchronous** for reads a shopper is waiting on and for the checkout sequence
that must report a result immediately. **Asynchronous** for everything that can
follow: notifications, fulfilment, audit.

The line is drawn at "does the shopper's next screen depend on it?". Making
everything asynchronous would have added eventual-consistency surprises to the
browse experience for no latency benefit; making everything synchronous would
have made notification latency part of the checkout critical path.

## 3. Checkout as a saga with compensations

Checkout spans inventory and payment. Two options:

| Approach | Verdict |
|---|---|
| Distributed transaction (2PC) | Rejected. Postgres and the payment provider cannot both enlist; vendor support is absent; blocking locks hurt throughput. |
| Saga with compensating actions | Chosen. Each step is local and fast; failures unwind explicitly. |

Compensations are business operations, not database rollbacks: releasing a stock
hold and voiding an authorisation both have external side effects that must be
driven deliberately.

**Every step is idempotent.** Not an optimisation, a requirement. Kafka delivers
at-least-once, so the saga will retry, and a non-idempotent step would
double-charge a customer.

## 4. Transactional outbox for order events

Publishing to Kafka inside the order transaction fails in one of two directions,
both unacceptable:

- Publish first, then commit: an event for an order that never existed.
- Commit first, then publish: a crash loses the event, and the customer paid for
  an order fulfilment never sees.

Writing the event to an outbox table in the same transaction as the order, then
relaying asynchronously, makes the two atomic in effect.

Cost, stated honestly: delivery is at-least-once, so consumers must be idempotent,
and there is a small publish delay (sub-second at the default poll interval).
That delay is the price for never losing a paid order.

## 5. Polyglot persistence

PostgreSQL for orders, payments, inventory, customers and shipping. MongoDB for
the catalogue, reviews and personalisation. Redis for carts, catalogue cache and
notification deduplication.

The split follows access pattern and data shape, not preference. Catalogue
documents vary enormously by category and are read far more than written, which
suits Mongo. Money needs transactional integrity and exact decimal arithmetic,
which suits Postgres and would be awkward and slow in Mongo.

## 6. Optimistic locking for stock

Two shoppers wanting the last unit is normal traffic, not an exception. Pessimistic
locking would serialise the hot row and reduce throughput at peak; optimistic
locking with bounded retries lets the uncontended majority proceed immediately.

Rejections surface as **409 Conflict**, not 500. "Someone bought it while you were
deciding" is an actionable, ordinary outcome, and the storefront shows an
alternative rather than a generic error.

Stock is modelled as `onHand`, `reserved` and `safetyStock`, with
`available = onHand - reserved - safetyStock`. Keeping `reserved` separate means a
reservation can be released without guessing how much to return, and abandoned
carts do not permanently consume availability.

A scheduled sweeper releases expired holds. Without it, every crashed checkout
would leave stock stranded until a human intervened, which on a busy catalogue
means phantom out-of-stock items.

## 7. Idempotency keys on money paths

Both `POST /orders` and `POST /payments/authorisations` require a client-supplied
idempotency key, backed by a unique database index.

A read-then-write check would be wrong: two concurrent retries would both see "no
existing order" and both create one. The unique index makes the database the
arbitrator, so exactly one wins.

Payment authorisations forward the key to the provider, which is what actually
protects against a double charge when this process dies mid-call.

## 8. Spring Boot, not a custom runtime

Chosen for the operational surface: mature ecosystem, first-class Actuator,
native Kubernetes support (liveness/readiness probes, graceful shutdown), and
Spring Cloud Gateway for edge concerns.

Rejected: Quarkus for lower memory footprint. The saving is real but modest next
to the migration cost of an unfamiliar reactive stack across eleven services, and
the team already operates Spring.

## 9. Service mesh via Istio

Gains that hand-written client code reliably gets wrong: mutual TLS with
verifiable service identity, authorization by identity, outlier ejection, and
centralised retry policy.

The retry configuration is the subtle part: it must not retry non-idempotent
operations. A mesh-level retry on `POST /payments` would double-charge customers,
so the retry rule matches idempotent verbs only, and retries just the 5xx that
mean the backend never answered — `502`, `503`, `504`. `500` is excluded on
purpose: it usually means the backend *did* process the request and failed
afterwards, so replaying it is not free of charge. The payment and order paths
are additionally governed by application-level idempotency.

The mesh runs in **ambient mode**: a shared per-node `ztunnel` gives every pod
mTLS and identity at no per-pod cost, and a shared per-namespace **waypoint**
supplies Layer 7 only for the services that need request-level routing, retries
or path-level authorization. Nothing is injected into the application, so
patching a mesh CVE does not restart a single application pod. Layer 7 is
enrolled per destination service rather than per namespace, so a Kafka-only
consumer does not pay for HTTP processing it never receives. See
[docs/Istio.md](docs/Istio.md).

## 10. Multi-tenancy resolved once, verified twice

The gateway resolves the tenant from the validated JWT and **strips** any
client-supplied `X-Tenant-ID` before forwarding. Without the strip step, a caller
could send the header directly and impersonate another tenant, since the header
alone would be trusted.

Downstream services re-resolve from their own JWT. This is defence in depth: a
compromised in-cluster workload could call a service directly, bypassing the
gateway entirely.

The tenant is part of every unique index and every Redis key, so a forgotten
filter in application code cannot leak another tenant's data even by mistake.

## 11. Observability: three pillars, wired once

Prometheus for metrics, Grafana for dashboards, Jaeger for traces, with
correlation IDs and Micrometer tracing provided by `common-observability` so
services do not each invent their own.

Sampling is per tier: 10% for request paths, 5% for the AI tier. Tracing everything
is expensive and, past a certain volume, produces traces nobody reads.

Business metrics are first class. `mesh_business_events_total` with a domain and
outcome tag is what makes "checkout success rate" a queryable metric rather than
an inference from HTTP status codes. This matters because a failed saga returns
200 with a `FAILED` status, so an HTTP-error-based alert would miss it entirely.

## 12. Secrets from Key Vault, mounted not injected

On AKS, the CSI driver mounts secrets as files. Secrets therefore never appear in
environment variables (visible via `/proc` and crash dumps) or in etcd.

The payment service stores only an opaque single-use token. No PAN ever reaches
the mesh, which keeps PCI scope to the provider's SDK.

## 13. Delivery

Jib builds container images without a Docker daemon, producing reproducible
layers with no build-time credential handling. Images go to ACR and deploys are
Helm-driven from one values file.

Rollouts use `maxUnavailable: 0` so capacity never dips, and a 45-second grace
period so in-flight checkouts finish and release their stock holds. Without that
grace period, every deploy would strand reservations and create phantom
out-of-stock items.

## Open questions

Honest gaps rather than implied solutions:

- **Recommendation model.** The shipped ranking is a transparent heuristic so it
  can be explained and regression-tested. A learned model needs a training
  pipeline, drift monitoring, and a fallback story.
- **Search.** Catalogue search is a prefix match. Production needs full-text or
  vector search, and the choice affects index design significantly.
- **Multi-region.** Single-region with zone redundancy. Active-active would need
  conflict resolution on the order and payment ledgers, which is a large design
  question not addressed here.
- **Partition-level ordering guarantees across regions.** Currently per-key
  ordering holds within a Kafka partition in one region only.
