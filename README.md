# Enterprise Multi-Tenant E-Commerce Mesh

An enterprise-grade, high-throughput e-commerce platform built on a cloud-native **Spring Boot Microservices Architecture**. This repository serves as a production-blueprint demonstrating **Domain-Driven Design (DDD)**, distributed transaction orchestration via the **Saga Pattern**, reliable async messaging using the **Transactional Outbox Pattern**, and strict multi-tenant isolation secured at the **API Gateway**.

---

## 🏗️ System Architecture

The platform is engineered as a decoupled network of specialized microservices communicating via synchronous REST APIs behind a central Ingress Gateway, alongside high-throughput asynchronous event streams managed by **Apache Kafka**.

```text
                        [ Client Requests ]
                                │
                                ▼
                       ┌─────────────────┐
                       │   API Gateway   │ (Rate Limiting, JWT Validation,
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

---

## 📦 Repository Structure & Module Blueprint

The project is structured as a **Maven Multi-Module Repository** to decouple business domains, segregate transactional processing tiers, and promote infrastructure reusability:

*   **`api-gateway/`** — Edge service implementing routing matrices, path-based filters, and resilient token-bucket rate limiting.
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
*   **Implementation Location:** See `OutboxEvent.java`, `OutboxEventRepository.java`, and `OutboxRelay.java` inside `order-service`.
*   **How it works:** Whenever an order transaction occurs, an `OutboxEvent` record containing the payload message is generated and committed within the **exact same database transaction** as the order state modification.
*   **Relay Mechanism:** A decoupled background polling worker (`OutboxRelay`) continually reads un-emitted records from the outbox table, writes them securely to Apache Kafka, and flags them as processed only after a valid cluster acknowledgment is returned.

### 3. Concurrency Protection & Stock Optimization
*   **Implementation Location:** See `InventoryReservation.java` and `ReservationExpirySweeper.java` inside `inventory-service`.
*   **High-Volume Protection:** Rather than using blocking row-level database locks that degrade throughput during concurrent flash-sales, the `inventory-service` applies an optimistic locking model and captures intent through transient transaction-locked `InventoryReservation` lines. If a race condition emerges, the thread surfaces a clean `ConcurrentStockUpdateException`.
*   **TTL Reclamation:** Abandoned or unpaid reservations are systematically scraped and reallocated to the available pool by a scheduled background daemon (`ReservationExpirySweeper`).

---

## 🛡️ Enterprise Security & Multi-Tenancy

*   **Tenant Forwarding Matrix:** The edge layer relies on a customized `TenantForwardingFilter` to discover incoming client organization tokens, construct thread-bound multi-tenant contexts, and seamlessly forward them into down-stream HTTP headers.
*   **Role-Based Isolation:** Integrated through custom Spring Security configuration filters mapping role scopes extracted straight from active OAuth2/JWT authorization matrices. The access profiles automatically segregate visibility boundaries (e.g., preventing parallel multi-tenant structures from viewing crossed catalog indexes or profile sets).

---

## 📊 Distributed Observability

Every system microservices instance mounts a structural trace filter (`CorrelationIdFilter`). It captures or constructs a unique UUID tracking chain identifier for all inbound request sequences. This correlation payload is programmatically mapped into outbound HTTP headers and appended directly into active Kafka `CloudEventHeaders`, generating an un-broken trace trail across the cluster that flows into **Prometheus and Grafana** dashboards.

---

## 🚀 Deployment & Local Setup

### System Prerequisites
Ensure your local environment includes:
*   Java 21/25 JDK
*   Apache Maven 3.9+
*   Docker & Docker Compose

### Running the Full Mesh Locally
1. Clone this repository to your workstation:
   ```bash
   git clone https://github.com
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


## Documentation

- [docs/AKS-DEPLOYMENT.md](docs/AKS-DEPLOYMENT.md) - cluster setup and deployment
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) - service design and data flow
- [docs/RUNBOOK.md](docs/RUNBOOK.md) - operational procedures and alerts
- [SYSTEM_DESIGN_HLD.md](SYSTEM_DESIGN_HLD.md) - high-level design decisions
