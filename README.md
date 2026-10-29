# Enterprise Multi-Tenant E-Commerce Mesh

[![CI](https://github.com/Vivek-Anbarasu/enterprise-ecommerce-mesh/actions/workflows/ci.yml/badge.svg)](https://github.com/Vivek-Anbarasu/enterprise-ecommerce-mesh/actions/workflows/ci.yml)

A twelve-service e-commerce backend on **Spring Boot 4.1 / Java 25**, deployed to **Azure AKS**
behind an **Istio ambient** mesh. Tenant-scoped at every hop, event-driven at the async edge, and
synchronous only where a shopper is actually waiting.

---

## Table of contents

- [Architecture](#architecture)
- [Services](#services)
- [Tech stack](#tech-stack)
- [Design decisions](#design-decisions)
- [Repository layout](#repository-layout)
- [Getting started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Start the infrastructure](#start-the-infrastructure)
  - [Build](#build)
  - [Run the services](#run-the-services)
- [Configuration](#configuration)
- [Checkout saga](#checkout-saga)
- [Messaging](#messaging)
- [Security and tenancy](#security-and-tenancy)
- [Observability](#observability)
- [Testing](#testing)
- [Deployment](#deployment)
- [Documentation index](#documentation-index)
- [License](#license)

---

## Architecture

```
                        ┌───────────────────────────────┐
  browser ──HTTPS──────▶│  Application Gateway for      │  WAF, TLS termination,
                        │  Containers (AGC)             │  edge rate limiting
                        └───────────────┬───────────────┘
                                        │  only AGC's CIDR may reach the pods
                    ┌───────────────────┴───────────────────┐
        /bff, /login│                                       │everything else
                    ▼                                       ▼
        ┌───────────────────────┐               ┌───────────────────────┐
        │     bff-service       │  bearer token │      api-gateway      │
        │        :8091          │──────────────▶│        :8080          │
        │ session cookie only;  │               │ JWT validation,       │
        │ tokens in Redis       │               │ per-tenant limits,    │
        └───────────────────────┘               │ circuit breakers      │
                                                └───────────┬───────────┘
                                                            │
   ┌────────────┬────────────┬────────────┬─────────────────┼────────────┬────────────┐
   ▼            ▼            ▼            ▼                 ▼            ▼            ▼
catalog     review      cart        customer            order        inventory   payment
:8085       :8088       :8086       :8087                :8082        :8083       :8084
   │            │            │            │                 │            │            │
   └── MongoDB/Redis ────────┘            └── PostgreSQL ───┴────────────┴────────────┘
                                                             │
                                                    order.events (Kafka)
                                                    │                    │
                                                    ▼                    ▼
                                          notification-service   shipping-delivery
                                          (no HTTP listener)     :8089
```

Two edge entry points, one certificate, routed by path: the SPA's `/bff` and `/login` go to the
BFF, everything else goes straight to the gateway exactly as it did before the BFF existed. That
means an unrecognised path fails safe rather than 404ing at the edge.

`ai-orchestrator-service` (`:8090`) is a peer of the catalogue services, reached through the
gateway like any other read path.

## Services

| Service | Port | Tier | Store | Responsibility |
|---|---|---|---|---|
| `api-gateway` | 8080 | edge | Redis | JWT validation, per-tenant rate limiting, routing, circuit breakers |
| `bff-service` | 8091 | edge | Redis | SPA session cookie; holds the Entra token bundle server-side and re-issues it as a bearer token |
| `product-catalog-service` | 8085 | edge | MongoDB, Redis | Products and categories; read-heavy, Redis in front |
| `review-rating-service` | 8088 | edge | MongoDB, Redis | Reviews, rating aggregates, moderation state |
| `shopping-cart-service` | 8086 | edge | Redis | Cart contents and totals |
| `ai-orchestrator-service` | 8090 | edge | MongoDB, Redis | Semantic product search, recommendations, shopping assistant |
| `customer-service` | 8087 | core | PostgreSQL | Profiles, addresses, preferences; system of record for shopper identity |
| `order-service` | 8082 | core | PostgreSQL | Checkout saga orchestrator; owns order lifecycle and the transactional outbox |
| `payment-service` | 8084 | core | PostgreSQL | Authorisation, capture, refunds |
| `inventory-service` | 8083 | core | PostgreSQL, Redis | Stock levels and reservations |
| `shipping-delivery-service` | 8089 | async | PostgreSQL | Shipment lifecycle, carrier integration |
| `notification-service` | — | async | Redis | Kafka-driven email/SMS/push. Headless: no business HTTP listener, probes and metrics on 8081 |

Each PostgreSQL service owns its own database (`orders_db`, `inventory_db`, `payments_db`,
`customers_db`, `shipping_db`). No service reads another's schema.

## Tech stack

| Concern | Choice |
|---|---|
| Runtime | Java 25, virtual threads (`spring.threads.virtual.enabled`) |
| Framework | Spring Boot 4.1.1, Spring Cloud 2025.1.3 |
| Persistence | PostgreSQL 15 + Flyway, MongoDB, Redis |
| Messaging | Kafka (KRaft), Debezium CDC out of the order outbox |
| Resilience | Resilience4j circuit breakers and bulkheads; timeout budgets in config, not code |
| Security | Spring Security OAuth2 resource server, Entra ID tokens, tenant claim resolution |
| API docs | springdoc-openapi 3.1.1 (`/swagger-ui.html`, `/v3/api-docs`) |
| Observability | OpenTelemetry (OTLP) → collector, Micrometer → Prometheus, Grafana, Jaeger locally |
| Mesh | Istio **ambient** (ztunnel L4 + shared waypoint L7), Gateway API |
| Cloud | Azure AKS, ACR, Key Vault, Service Bus/Event Hubs, Azure Monitor managed Prometheus |
| Packaging | Jib (primary, daemonless), `docker/Dockerfile.template` (fallback), Helm, Bicep |
| Testing | JUnit 5, Mockito, AssertJ, H2 for context slices, surefire/failsafe; Testcontainers declared for future ITs |

## Design decisions

The decisions that shape this repository are the ones with a **cost**. Each is recorded as
an ADR under [`docs/adr/`](docs/adr/README.md) with the alternatives that were rejected and
the measurable conditions under which it should be reopened.

| Decision | Why | Trade-off accepted |
| --- | --- | --- |
| **Checkout is a compensatable orchestrator saga** — [ADR-0001](docs/adr/0001-checkout-saga-orchestration.md) | A shopper is waiting, so the flow must be synchronous and must be able to undo steps that already committed. | The orchestrator is a coupling point and needs a sweeper; an ambiguous authorise timeout still leaves an orphaned hold at the provider. |
| **Events leave through a transactional outbox, read by Debezium** — [ADR-0002](docs/adr/0002-transactional-outbox-with-debezium-cdc.md) | The order row and its event must commit together; a dual write can commit one and lose the other. | CDC becomes an operational dependency, and the payload column becomes a wire contract. |
| **One schema per service, a tenant column on every aggregate** — [ADR-0003](docs/adr/0003-shared-schema-multi-tenancy.md) | Many tenants share one deployment, and a schema change is one migration. | Every query must be tenant-filtered; isolation is enforced in code, not by the database. |
| **Brute-force cosine instead of a vector database** — [ADR-0004](docs/adr/0004-brute-force-semantic-search.md) | A few thousand 768-dimension vectors scan in single-digit milliseconds; a vector store adds a deployment and an outage mode. | Search is O(n) per query and memory-resident; the crossover is about ten thousand products. |
| **The BFF holds the token bundle; the browser holds a session cookie** — [ADR-0005](docs/adr/0005-bff-server-side-token-storage.md) | An XSS bug must not be able to exfiltrate an access token. | One extra hop, Redis on the authentication path, and CSRF to think about. |
| **A database per service, no shared schema** | Services must be deployable and migratable independently. | Cross-service joins become API calls; reporting needs its own path. |
| **Istio ambient rather than a sidecar per pod** | ztunnel gives mTLS at L4 without a proxy in every pod; only the waypoint pays for L7. | Ambient is newer than sidecar mode and has fewer field-years. |
| **Timeouts and circuit breakers in configuration, not code** | A hung dependency must fail fast, and the breaker — not a long timeout — is what absorbs slowness. | Budgets have to be tuned per environment, and a wrong budget is invisible until it matters. |

### What this architecture deliberately does not do

- **No distributed transactions.** Checkout is a saga with durable compensation, not 2PC.
- **No shared database.** A cross-service read is an API call, and a schema is owned by one
  service.
- **No token in the browser.** The SPA holds an HttpOnly session cookie and nothing else.
- **No vector database.** Similarity is a scan until the catalogue makes that wrong, and the
  crossover point is written down.
- **No sidecar per pod.** The mesh is ambient; L7 is only paid for where L7 is used.
- **No synchronous call in the async edge.** Notification and shipping are Kafka-driven and
  have no business HTTP listener.

## Repository layout

```
common-bom/                 shared dependency management
  common-messaging/         topics, retry, dead-letter recovery, outbox support
  common-observability/     metrics, tracing, structured logging
  common-security/          JWT, tenant context, RFC 9457 handlers
api-gateway/                Spring Cloud Gateway edge
bff-service/                session-cookie BFF for the SPA
core-services/              order, payment, customer
catalog-services/           product-catalog, review-rating
transactional-caches/       inventory, shopping-cart
async-processors/           notification, shipping-delivery
ai-orchestrator-service/    semantic search and recommendations

docker/                     local infrastructure init scripts + Dockerfile template
deploy/                     everything applied to a cluster
  helm/                     the release (chart + prod overrides)
  bicep/                    Azure WAF policy and alert rules
  istiod/                   cluster-scoped MeshConfig fragments
  azure/                    Azure observability runbook
  kubernetes/               plain-YAML reference manifests (base, debezium)
  observability/            Prometheus, Grafana, OTel collector, Azure Monitor rules
hld/                        architecture diagram (drawio)
docker-compose.yml          local stateful dependencies only
```

The rule: if it ends up in a cluster it lives under `deploy/`; if it only runs on a laptop or
builds an image, it does not.

## Getting started

### Prerequisites

- **JDK 25**
- **Maven 3.9+** (no wrapper is checked in)
- **Docker** with Compose (the build's tests use Testcontainers)

### Start the infrastructure

The compose file brings up stateful dependencies only — no application containers — so a code
change needs no image rebuild.

```bash
docker compose up -d
```

| Endpoint | URL |
|---|---|
| PostgreSQL | `localhost:5432` (`postgres` / `postgres`) |
| MongoDB | `localhost:27017` |
| Redis | `localhost:6379` |
| Kafka (host listener) | `localhost:29092` |
| Jaeger UI | http://localhost:16686 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (`admin` / `admin`) |

The Kafka external listener is `29092`, not `9092` — `9092` is only reachable from inside the
compose network and from containers. Host-run services must set `KAFKA_BOOTSTRAP_SERVERS`.

### Build

```bash
mvn clean install
```

Add `-DskipTests` for a fast build. Modules depend on the `common-bom` artifacts, so a single
module cannot be built in isolation before the first full install.

### Run the services

Each service reads its peer URLs from environment variables that default to in-cluster DNS names
(`http://inventory-service:8083`). Running on the host means overriding them:

```bash
export KAFKA_BOOTSTRAP_SERVERS=localhost:29092
export OTLP_ENDPOINT=http://localhost:4318/v1/traces

# api-gateway's routing targets
export MESH_CATALOG_URI=http://localhost:8085
export MESH_REVIEW_URI=http://localhost:8088
export MESH_CUSTOMER_URI=http://localhost:8087
export MESH_CART_URI=http://localhost:8086
export MESH_ORDER_URI=http://localhost:8082
export MESH_PAYMENT_URI=http://localhost:8084
export MESH_INVENTORY_URI=http://localhost:8083
```

```bash
mvn -pl core-services/order-service spring-boot:run
```

`order-service` additionally needs `INVENTORY_URI=http://localhost:8083` and
`PAYMENT_URI=http://localhost:8084`. Start the gateway last.

Without the `entra` profile the gateway starts **without** token validation and logs a warning.
Catalogue reads (`GET /api/products/**`, `/api/categories/**`, `/api/reviews/**`) are anonymous;
everything else returns 401. That is the fail-closed default, not a bug — set the `entra` profile
to validate real tokens.

Per-service health and metrics are on `/actuator/health` and `/actuator/prometheus`.

## Configuration

Everything is environment-substituted with a local default, so the same jar runs in both places.

| Variable | Default | Notes |
|---|---|---|
| `MESH_ENV` | `local` | Gates safety checks; production refuses to start on insecure settings |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/<db>` / `postgres` / `postgres` | Per service |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Use `localhost:29092` from the host |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | |
| `OTLP_ENDPOINT` | `http://localhost:4318/v1/traces` | |
| `TRACING_SAMPLE_RATE` | `0.1` | |
| `LOG_FORMAT` | `ecs` | Structured console logging |
| `KAFKA_SECURITY_PROTOCOL` / `KAFKA_SASL_JAAS_CONFIG` | `PLAINTEXT` / empty | Event Hubs uses `SASL_SSL` |
| `ENTRA_TENANT_ID` / `ENTRA_CLIENT_ID` / `ENTRA_REDIRECT_URI` | unset | `entra` profile only |

Profiles are used sparingly: `entra` on the gateway and the BFF. Everything else is plain
`application.yml`.

## Checkout saga

Checkout is the one flow that must be synchronous, because a shopper is waiting on the result.
It is a **compensatable saga** driven by `CheckoutSagaOrchestrator` in `order-service`, not by
messages: an orchestrator that fires a request onto a topic and returns has nothing to compensate
against when the step fails.

```
reserve inventory ──▶ authorise payment ──▶ confirm order
       │                       │
       └──── compensate ◀──────┘
```

- **Claims** — each step is claimed with a lease before it is attempted, so a crashed
  orchestrator cannot be resumed by a second replica mid-flight.
- **Timeouts** — the saga's `RestClient` has a connect/read budget (`saga.connect-timeout`,
  `saga.read-timeout`); without one, a hung participant holds the shopper's request open forever.
- **Compensation** — failed checkouts enqueue compensation actions in PostgreSQL. A worker retries
  them with exponential backoff, so a payment service that is down for five minutes is refunded
  when it returns rather than being written off.
- **Recovery** — `StaleSagaSweeper` fails checkouts whose lease expired mid-flight, then
  compensates them.
- **Circuit breakers** — inventory and payment each have one. A `409 Sold out` from inventory is a
  legitimate answer, not a failure, and does not count toward the breaker's error rate.

Confirmed orders are published through a **transactional outbox** written in the same transaction
as the order row. `order.events` is produced by Debezium reading the outbox table out of the WAL;
there is no polling relay and therefore no gap between commit and publish.

## Messaging

Only topics with a real producer *and* consumer are declared in `MessagingTopics`:

| Topic | Producer | Consumer |
|---|---|---|
| `order.events` | order-service (via outbox → Debezium) | notification-service |
| `product.events` | product-catalog-service | ai-orchestrator-service (keeps the semantic-search vector index in step) |

Conventions:

- Name is `<domain>.events`; every topic is partitioned and keyed by aggregate id, so ordering is
  guaranteed per aggregate while throughput scales horizontally.
- Dead letters go to `<topic>.dlq`. Offsets are committed **after** the handler returns
  (`enable-auto-commit: false`), so a crash replays rather than loses.
- Consumer group ids live on the `@KafkaListener` annotation, not in `application.yml`, where they
  would be silently ignored.

## Security and tenancy

- **Edge** — AGC terminates TLS and enforces the WAF; only its CIDR is allowed to reach the pods
  (`edgeOnly` NetworkPolicies), so an in-cluster pod cannot bypass it.
- **Token** — the gateway is an OAuth2 resource server over Entra ID tokens. The `entra` profile
  pins both `issuer-uri` and `jwk-set-uri`; discovering the JWKS without pinning the issuer would
  accept a token minted for any tenant in the directory.
- **Tenant** — resolved from the JWT `tenant_id` claim first, falling back to `X-Tenant-ID` only
  when there is no validated token. The header is authoritative only for pre-auth traffic; a
  token is never overridden by it. Resolution lands in `TenantContext` and in MDC.
- **Authorisation** — roles come from the token (notably `ADMIN`); errors are rendered as
  RFC 9457 problem details with the same shape across services.
- **BFF** — never speaks OAuth2 to the browser. The SPA gets a session cookie; the token bundle
  stays server-side in Redis and is refreshed silently.

## Observability

- **Traces** — OTLP HTTP to the collector (Jaeger locally, Application Insights in Azure). Istio's
  waypoint emits into the same pipeline; see `deploy/istiod/meshconfig-tracing.yaml`.
- **Metrics** — Micrometer → Prometheus. Locally scraped via
  `deploy/observability/prometheus/prometheus.yml`; in Azure, Azure Monitor managed Prometheus
  through the PodMonitors in `deploy/observability/amp/`.
- **Logs** — structured, ECS-shaped, one line per event.
- **Alerts** — PromQL catalogue in `deploy/observability/amp/rules.yaml`, deployed by
  `deploy/bicep/alerting.bicep`. Business metrics (order failures, DLQ fills) are emitted from
  application code rather than inferred from HTTP status codes.

## Testing

```bash
mvn test          # 107 tests, ~75 seconds, no Docker required
mvn verify        # same suite plus failsafe; skipITs=true until the first *IT exists
```

The suite needs no Docker, no database and no broker. Redis and Kafka clients connect lazily,
so contexts start without them; the inventory slices run on H2 in PostgreSQL mode; everything
else is mocked at the boundary it is testing. CI runs `mvn verify` on every push.

The tests are shaped around properties a happy-path test would miss:

- **Architecture tests** — `SagaTransactionBoundaryTest` asserts by reflection that the
  orchestrator carries no `@Transactional` and that every state-store write does, so a later
  refactor cannot silently restore the connection-and-row-lock window around two HTTP calls.
- **Wiring tests** — `OrderEventListenerWiringTest` asserts the listener container and
  consumer group from the annotation, because a group id in `application.yml` is silently
  ignored.
- **Configuration tests** — the cache tests assert the `CacheManager` the service actually
  ships with, not a test substitute, and that the running TTL matches the constant eviction
  uses.

Testcontainers is declared for the modules that own a database, so an integration test can be
added without touching a POM, but no test currently starts a container. The schema under test
is the Flyway migration that ships, not a hand-written parallel one.

## Deployment

Images are built with **Jib**; no Docker daemon is required.

```bash
mvn -Pcontainer package   # writes <artifactId>-<version>.tar per module
mvn -Pjib package         # pushes to ${docker.registry}/${docker.namespace}/...
```

The default target is `acrenterprise.azurecr.io/ecommerce/<service>:1.0.0`.

**Helm** is the only supported path to a cluster, and it owns every service except the two edge
workloads kept as reference manifests.

```bash
helm upgrade --install ecommerce deploy/helm/enterprise-ecommerce \
  -n ecommerce --create-namespace \
  -f deploy/helm/values-prod.yaml
```

Cluster prerequisites, in order:

1. Istio ambient (`istioctl install --set profile=ambient`) with the Gateway API experimental
   CRDs — the resilience HTTPRoutes use `retry`.
2. The istiod tracing fragment, before enabling `istio.tracingProvider`. Referencing a provider
   that is not defined makes `istioctl` reject the whole `Telemetry` resource.
   ```bash
   helm upgrade istiod istio/istiod -n istio-system \
     -f deploy/istiod/meshconfig-tracing.yaml
   ```
3. cert-manager or Key Vault CSI, for the edge certificate.
4. Application Gateway for Containers, then the chart.

**Azure** infrastructure that cannot be expressed as Kubernetes objects lives in `deploy/bicep`:
the WAF policy is an ARM child resource of the Application Gateway, and the alert rules are Azure
Monitor resources. Neither is created by the chart, and the chart fails loudly if you enable them
without supplying the ARM IDs.

Reference manifests under `deploy/kubernetes/` are for reading and for a `kubectl`-only bootstrap;
they intentionally do not duplicate the mesh policy, which is rendered from
`deploy/helm/enterprise-ecommerce/templates/istio.yaml`.

## Documentation index

| Document | Contents |
|---|---|
| [`docs/adr/`](docs/adr/README.md) | The five architecture decisions above, with alternatives, consequences and revisit triggers |
| [`deploy/azure/README.md`](deploy/azure/README.md) | Azure Monitor workspace, managed Prometheus, App Insights, Managed Grafana, alerting order of operations |
| [`deploy/bicep/README.md`](deploy/bicep/README.md) | WAF policy and alert rules; what Bicep owns versus the chart |
| [`deploy/istiod/README.md`](deploy/istiod/README.md) | Why the tracing provider lives in `MeshConfig` and not in the chart |
| [`deploy/helm/enterprise-ecommerce/values.yaml`](deploy/helm/enterprise-ecommerce/values.yaml) | Per-service configuration, tiers, waypoint enrolment, edge exposure |
| [`deploy/kubernetes/debezium/order-outbox-connector.yaml`](deploy/kubernetes/debezium/order-outbox-connector.yaml) | Outbox CDC connector |
| [`hld/hld-mesh-architecture.drawio`](hld/hld-mesh-architecture.drawio) | High-level design diagram |
| [`docker-compose.yml`](docker-compose.yml) | Local infrastructure, with the reasoning for each setting inline |

## License

MIT — see [LICENSE](LICENSE).
