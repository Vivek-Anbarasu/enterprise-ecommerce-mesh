# Enterprise Multi-Tenant E-Commerce Mesh

An enterprise-grade, high-throughput e-commerce platform built on a cloud-native **Spring Boot Microservices Architecture**. This repository serves as a production-blueprint demonstrating **Domain-Driven Design (DDD)**, distributed transaction orchestration via the **Saga Pattern**, reliable async messaging using the **Transactional Outbox Pattern**, strict multi-tenant isolation, and a browser-facing **Backend-for-Frontend** layer that keeps OAuth2 tokens out of the browser.

---

## 🏗️ System Architecture

The platform is engineered as a decoupled network of specialized microservices communicating via synchronous REST APIs behind a central Ingress Gateway, alongside high-throughput asynchronous event streams managed by **Apache Kafka**.

```text
                        [ Client Requests ]
                                 │
                                 ▼
                     ┌───────────────────────┐
   Browser ─────────▶│       BFF Layer      │  (Session cookie in, Redis
   (React SPA)       │      bff-service      │   token bundle, silent refresh,
                     └───────────┬───────────┘   response aggregation)
                                 │  Bearer token
                                 ▼
                        ┌─────────────────┐
   Mobile / Partner ───▶│   API Gateway   │ (Rate Limiting, JWT Validation,
                        └────────┬────────┘  Tenant Header Propagation)
                                 │
         ┌───────────────────────┼───────────────────────┐
         ▼                       ▼                       ▼
 ┌──────────────┐        ┌──────────────┐        ┌──────────────┐
 │ Catalog Svc  │        │  Cart Svc    │        │  Order Svc   │ (Saga Orchestrator &
 └──────────────┘        └──────────────┘        └──────┬───────┘  Outbox Store)
                                                        │
                                                        ▼ (Transactional Outbox)
                                                ┌──────────────┐
                                                │ Outbox Relay │
                                                └──────┬───────┘
                                                        │
                                                        ▼ [ Apache Kafka ]
                                                        │
                                       ┌───────────────┴───────────────┐
                                       ▼                               ▼
                               ┌──────────────┐                ┌──────────────┐
                               │ Shipping Svc │                │ Notification │
                               └──────────────┘                └──────────────┘
```

The **BFF** is the only workload the browser talks to. It terminates an opaque `HttpOnly` session cookie, keeps the Entra ID token bundle server-side in Redis, refreshes it silently before it expires, and re-issues it as a bearer token to the gateway. The SPA therefore never holds a token, and a stolen session cookie is worth one idle-timeout window rather than an entire token lifetime. It sits **in front of** the gateway rather than behind it, so the gateway remains the single mesh entry point for every other client. Full design: [docs/BFF-SESSION.md](docs/BFF-SESSION.md).

---

## 📦 Repository Structure & Module Blueprint

The project is structured as a **Maven Multi-Module Repository** to decouple business domains, segregate transactional processing tiers, and promote infrastructure reusability:

*   **`api-gateway/`** — Edge service implementing routing matrices, path-based filters, and resilient token-bucket rate limiting. Terminates all non-browser client traffic.
*   **`bff-service/`** — Browser-facing backend for frontend. OAuth2 authorization-code login against Microsoft Entra ID, a Redis-backed session holding the token bundle, silent refresh with an early-expiry skew window, CSRF protection for the cookie-authenticated surface, and concurrent multi-service response aggregation that degrades per section rather than failing wholesale.
*   **`common-bom/`** — Centralized infrastructure parent containing shareable, modular Spring Boot auto-configurations:
    *   `common-security`: Reusable JWT claims extraction, tenant context filtering, and RFC-7807 problem detail exception mapping.
    *   `common-messaging`: Abstracted Kafka event publishers, consumer profiling, and automated cloud headers management.
    *   `common-observability`: Distributed tracing interceptors mapping unified correlation IDs across thread boundaries and network hops.
*   **`core-services/`** — Transactional source-of-truth microservices:
    *   `order-service`: Core domain orchestrating multi-step checkout lifecycles and managing database-backed state transitions.
    *   `customer-service`: Domain tracking customer registrations, operational profiles, and address aggregates.
    *   `payment-service`: Payment processor isolating remote banking integrations from the downstream state machine.
*   **`transactional-caches/`** — Write-heavy layers utilizing fast locking structures:
    *   `inventory-service`: Real-time stock reservation matrix utilizing explicit stock line items to prevent double-selling.
    *   `shopping-cart-service`: High-concurrency runtime handling session-state caching and rapid additions/modifications.
*   **`catalog-services/`** — Read-optimized storefront operational domains:
    *   `product-catalog-service`: Highly scalable queries returning indexed item properties, models, and tiered pricing.
    *   `review-rating-service`: Service tracking product quality indexing, aggregate ratings, and customer feedback.
*   **`async-processors/`** — Decoupled event consumers processing heavy workflows asynchronously:
    *   `shipping-delivery-service`: Carrier lifecycle listener managing physical fulfillment and third-party dispatches.
    *   `notification-service`: Multi-channel communication engine executing asynchronous transactional notifications.
*   **`ai-orchestrator-service/`** — Intelligence component tracking client purchase profiles and serving real-time affinity recommendations.
*   **`deploy/`** — Production deployment infrastructure containing Docker definitions and localized Kubernetes Helm charts.

---

## ⚡ Core Distributed Systems Patterns (Interview Deep Dives)

### 1. Distributed Transaction Orchestration (The Saga Pattern)
To ensure ultimate data consistency across distinct database boundaries without resorting to slow, blocking Two-Phase Commit (2PC) blocks, the project utilizes an **Orchestrator-Based Saga**.
*   **Implementation Location:** See `CheckoutSagaOrchestrator.java` inside `order-service`.
*   **How it works:** When a client initiates checkout, the `order-service` creates a pending record and coordinates a sequential workflow across services. It issues commands to reserve inventory (`inventory-service`) and process charges (`payment-service`).
*   **Compensating Transactions:** If the `payment-service` throws a failure or returns a decline code, the orchestrator catches the event and broadcasts back-tracking compensation requests to the `inventory-service` to immediately clear reservations and release stocks seamlessly.

### 2. Reliable Asynchronous Event Streaming (Transactional Outbox Pattern)
To avoid data loss resulting from network hiccups or messaging broker downtime during database operations, the platform isolates DB commits from Kafka broadcasts.
*   **Implementation Location:** See `OutboxEvent.java`, `OutboxEventRepository.java`, and `OutboxService.java` inside `order-service`.
*   **How it works:** Whenever an order transaction occurs, an append-only `OutboxEvent` record containing the payload message is written within the **exact same database transaction** as the order state modification, so "order persisted" and "event recorded" commit or roll back together.
*   **Delivery Mechanism:** Delivery is Change Data Capture (CDC). Debezium tails the PostgreSQL write-ahead log and its outbox event router publishes every inserted row to Apache Kafka. There is no polling relay and no `published_at` bookkeeping - the WAL, not a column, is the record of what was emitted, so nothing can fall behind a claim lock.

### 3. Concurrency Protection & Stock Optimization
*   **Implementation Location:** See `InventoryReservation.java` and `ReservationExpirySweeper.java` inside `inventory-service`.
*   **High-Volume Protection:** Rather than using blocking row-level database locks that degrade throughput during concurrent flash-sales, the `inventory-service` applies an optimistic locking model and captures intent through transient transaction-locked `InventoryReservation` lines. If a race condition emerges, the thread surfaces a clean `ConcurrentStockUpdateException`.
*   **TTL Reclamation:** Abandoned or unpaid reservations are systematically scraped and reallocated to the available pool by a scheduled background daemon (`ReservationExpirySweeper`).

### 4. Browser Sessions Without Tokens in the Browser (BFF Pattern)
*   **Implementation Location:** See `SessionTokenService.java` and `BffAggregationService.java` inside `bff-service`.
*   **How it works:** The browser holds one opaque, `HttpOnly`, `SameSite=Lax` cookie naming a server-side session in Redis. The token bundle lives in that session as a framework-owned `OAuth2AuthorizedClient`. On every request the service resolves the session to a live access token, refreshing it **90 seconds before expiry** rather than on expiry, so no request is ever lost to a clock boundary.
*   **Concurrency:** Resolution is serialised on a striped per-session lock. Entra rotates refresh tokens on use, so two concurrent refreshes would race and one would be rejected; the loser re-reads the session and reuses the winner's tokens rather than failing the shopper.
*   **Why it matters:** CSRF protection must stay **enabled** here, unlike on every stateless service in the mesh — a cookie is attached by the browser with no cooperation from the SPA. Full design, including the failure modes: [docs/BFF-SESSION.md](docs/BFF-SESSION.md).

---

## 🛡️ Enterprise Security & Multi-Tenancy

*   **Tenant Forwarding Matrix:** The edge layer relies on a customized `TenantForwardingFilter` to discover incoming client organization tokens, construct thread-bound multi-tenant contexts, and seamlessly forward them into down-stream HTTP headers. Any client-supplied `X-Tenant-ID` is **deleted** and re-derived from the validated JWT, so a forged header cannot win.
*   **Role-Based Isolation:** Integrated through custom Spring Security configuration filters mapping role scopes extracted straight from active OAuth2/JWT authorization matrices. The access profiles automatically segregate visibility boundaries (e.g., preventing parallel multi-tenant structures from viewing crossed catalog indexes or profile sets).
*   **Credential Containment:** The gateway strips `Cookie` from every forwarded request. Combined with the BFF's server-side token bundle, a session identifier never travels past the edge that issued it, and downstream services are authenticated by the bearer token alone.
*   **Enumerated CORS Origins at the Browser Edge:** The BFF overrides the shared wildcard CORS source with exact storefront origins, because a wildcard pattern paired with `allowCredentials(true)` would defeat `SameSite` on the session cookie.

---

## 📊 Distributed Observability

Every system microservices instance mounts a structural trace filter (`CorrelationIdFilter`). It captures or constructs a unique UUID tracking chain identifier for all inbound request sequences. This correlation payload is programmatically mapped into outbound HTTP headers and appended directly into active Kafka `CloudEventHeaders`, generating an un-broken trace trail across the cluster that flows into **Prometheus and Grafana** dashboards.

The BFF additionally reports session and aggregation outcomes as business metrics under the `bff_session` and `bff_aggregation` domains — including `refresh_raced_recovered` and `no_refresh_token`, the latter of which is worth alerting on because it means a scope list has lost `offline_access` and every session will silently die an hour later.

---

## 🚀 Deployment & Local Setup

### System Prerequisites
Ensure your local environment includes:
*   Java 25 JDK
*   Apache Maven 3.9+
*   Docker & Docker Compose

### Running the Full Mesh Locally
1. Clone this repository to your workstation, then enter the project directory:
   ```bash
   cd enterprise-ecommerce-mesh
   ```
2. Build all services and compile shared libraries via Maven:
   ```bash
   mvn clean install -DskipTests
   ```
3. Boot the infrastructure (PostgreSQL, Kafka, Redis) along with all core microservices:
   ```bash
   docker compose up -d --build
   ```

The BFF boots without any identity-provider configuration and logs a warning; only sign-in is unavailable until it is given an Entra client id and tenant id. See [docs/BFF-SESSION.md](docs/BFF-SESSION.md).

---

## Documentation

- [docs/REQUEST-FLOW-END-TO-END.md](docs/REQUEST-FLOW-END-TO-END.md) - end-to-end request flow across all eight request paths, hop by hop
- [docs/BFF-SESSION.md](docs/BFF-SESSION.md) - BFF session model, silent refresh, aggregation and its failure modes
- [docs/AKS-DEPLOYMENT.md](docs/AKS-DEPLOYMENT.md) - cluster setup and deployment
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) - service design and data flow
- [docs/RUNBOOK.md](docs/RUNBOOK.md) - operational procedures and alerts
- [docs/SYSTEM_DESIGN_HLD.md](docs/SYSTEM_DESIGN_HLD.md) - high-level design decisions