# Enterprise Multi-Tenant E-Commerce Mesh
## End-to-End Architectural & Business Logic Blueprint

This document provides an exhaustive, production-grade functional breakdown of the **Enterprise Multi-Tenant E-Commerce Mesh** project. It is structured specifically to serve as a deep-dive preparation guide for system design panels, principal architecture reviews, and GitHub documentation.

---

## 1. Executive Project Summary & Core Technical Stack

The **Enterprise Multi-Tenant E-Commerce Mesh** is a cloud-native, highly available, distributed e-commerce architecture designed to operate under strict multi-tenant constraints. Rather than a traditional monolithic or naive microservices layout, this system implements high-throughput patterns to solve data isolation, asynchronous distributed consistency, and high-concurrency race conditions.

### Core Architecture Highlights
* **Multi-Tenancy:** Single-deployment, logical data isolation using reactive context propagation.
* **Distributed Consistency:** Orchestrator-Based **Saga Pattern** for transactional checkouts across boundary lines without utilizing low-performance blocking Two-Phase Commits (2PC).
* **Asynchronous Resilience:** **Transactional Outbox Pattern** to ensure guaranteed event delivery to messaging brokers under network degradation or node failure.
* **Concurrency Guardrails:** Optimistic concurrency tokens coupled with native database atomic locks to defeat double-selling vectors during hyper-concurrency flash sales.

### Modular Technology Stack Mapping
* **Core Application Framework:** Java 25, Spring Boot 4.1.x
* **Ingress & Traffic Control:** Spring Cloud Gateway, Project Reactor (WebFlux), Redis (Token-Bucket Rate Limiter)
* **Storage Tiers & Mapping:** PostgreSQL (Domain Databases), Spring Data JPA, Hibernate, Redis (Session/Cart Cache)
* **Message Broker Architecture:** Apache Kafka (Event Streams), CloudEvents Standard Meta-payloads
* **Observability & Diagnostics:** OpenTelemetry, Micrometer, Prometheus, Grafana, MDC (Mapped Diagnostic Context) Tracking
* **Cloud & Operations Blueprint:** Docker, Kubernetes Helm Charts, Istio Service Mesh Routing Matrix

---

## 2. Real-Time User Journey: The Checkout Scenario

To comprehend how these services interact, we follow a single real-world transaction: 
**A customer belongs to Tenant-A ("PremiumRetailGlobal") and attempts to purchase 2 units of a high-demand item during a Flash Sale.**

```
[Customer Request] 
       │ (Header: X-Tenant-ID: Tenant-A)
       ▼
┌──────────────┐      Validates JWT, Checks Redis Rate-Limit
│ API Gateway  ├───────────────────────────────────────────────────────┐
└──────┬───────┘                                                       ▼
       │ Context Propagated via Reactive Stream Context        ┌───────────────┐
       ▼                                                       │  Redis Bucket │
┌──────────────┐                                               └───────────────┘
│ Order Service│ ───► Creates Order (PENDING_CHECKOUT) & Outbox Log
└──────┬───────┘
       │
       ├─► [Command 1: Reserve Stock] ──► ┌───────────────────┐
       │                                  │ Inventory Service │ (Optimistic Row Lock)
       │                                  └─────────┬─────────┘
       │                                            │ Success
       │                                            ▼
       ├─► [Command 2: Authorize Fund] ─► ┌───────────────────┐
       │                                  │  Payment Service  │ (Interacts with Bank PG)
       │                                  └─────────┬─────────┘
       │                                            │ FAILURE (Declined Card)
       ▼                                            ▼
 [Trigger Compensation] ────────────────────────────┘
       │
       └─► [Rollback Stock] ────────────► ┌───────────────────┐
                                          │ Inventory Service │ (Releases Stock Block)
                                          └───────────────────┘
```

### Stage 1: Ingress Control and Identity Binding
1. **Network Entry:** The customer submits a `POST /api/v1/orders/checkout` payload through their browser interface. The HTTP request contains the header `X-Tenant-ID: tenant_premium_01` along with an OAuth2 JWT authorization bearer token.
2. **Gateway Interception:** The request hits the `api-gateway`. The gateway runs the incoming traffic through the `GatewaySecurityConfig` pipeline. 
3. **Tenant Context Generation:** The custom `TenantForwardingFilter` extracts the tenant header. Because the gateway runs on non-blocking WebFlux threads, it attaches this attribute directly to the **Project Reactor Context Container**. This ensures the tenant ID is immutable and bounds correctly across the asynchronous pipeline.
4. **Rate-Limiting Check:** The gateway evaluates the tenant's profile via `RateLimitKeyResolverConfig`. It runs a **Redis Token-Bucket algorithm** matching the Tenant ID. If the tenant has exceeded their tier limit (e.g., 5,000 requests/min), it instantly blocks the call with an HTTP `429 Too Many Requests` code, preserving backend health.

### Stage 2: Intent Creation & Transaction Boundary Setting
1. **Routing to Domain:** Assuming traffic checks pass, the gateway proxies the payload downstream to the `order-service`.
2. **Trace ID Propagation:** The `CorrelationIdFilter` within the service intercepts the request, checks for an existing `X-Correlation-ID` header, and maps it to the logging frame using **MDC (Mapped Diagnostic Context)**. Every downstream log line printed henceforth is tagged with this explicit workflow token.
3. **Pending Order Commit:** The `OrderController` hands the payload to the business logic layer. The service initializes a new `Order` aggregate structure with a status of `PENDING_CHECKOUT`. It commits this record into the isolated tenant database layer using Spring Data JPA.

### Stage 3: Distributed Coordination (The Saga Implementation)
Because the platform follows a database-per-service pattern, the `order-service` cannot mutate the inventory or payment tables directly. It initiates the **Orchestrator-Based Saga Pattern**.

1. **Saga Triggering:** The `CheckoutSagaOrchestrator` assumes control of the execution loop. It tracks the step execution metrics across state machine transitions.
2. **Inventory Stock Lock Call:** The orchestrator dispatches an internal REST call to the `inventory-service` invoking an stock check-and-reserve transaction.
3. **Concurrency Resolution in Inventory:** 
   * The `inventory-service` receives the directive. The database initialization script (`V1__inventory_schema.sql`) isolates item indexes.
   * The application uses **Optimistic Concurrency Control (OCC)** combined with atomic database level math operations inside `StockMutationService.java`.
   * It attempts to commit an `InventoryReservation` line item block. If another thread grabbed the same item simultaneously, a JPA version collision triggers a `ConcurrentStockUpdateException`. The service cleanly surfaces this to the orchestrator, preventing a double-selling state.
   * Assuming success, the reservation is written, and stock count drops provisionally.

### Stage 4: Financial Verification & Cascading Rollbacks
1. **Payment Execution:** Upon receipt of a successful inventory reservation signal, the `CheckoutSagaOrchestrator` fires a payload command to the `payment-service`.
2. **Third-Party Handshake:** The `PaymentController` delegates processing to `PaymentService.java`, which performs an outbound API connection to a live bank interface utilizing the configuration checks defined within `PaymentGatewaySafetyCheck.java`.
3. **Handling Financial Fault Vectors:** For our real-time scenario, **the customer's bank card is declined due to insufficient balances.** The payment module traps the external bank response code and returns a clean transaction response indicating failure.
4. **Triggering Compensation Logic:** The `CheckoutSagaOrchestrator` intercepts the payment failure. It must roll back all previous state updates to keep the distributed system structurally sound.
5. **Compensating Action Dispatch:** The orchestrator fires an explicit asynchronous request back to the `inventory-service` instructing it to void reservation hashes. The `inventory-service` executes the command, returns the 2 reserved stock units back to the available inventory ledger, and changes the log record. The `order-service` switches the master order state status from `PENDING_CHECKOUT` to `FAILED_PAYMENT_DECLINED`.

### Stage 5: Reliable Asynchronous State Synchronization
Even if a transaction fails or succeeds, downstream tracking systems (Analytics, Warehouses, CRM) must receive notification data with 100% guarantee. 

1. **Writing to the Outbox:** When the order status is updated to `FAILED_PAYMENT_DECLINED`, the `order-service` business logic writes an append-only `OutboxEvent` entity into its PostgreSQL schema (table `outbox_events`) within the **exact same database transaction** that updated the order table. The event body goes in `payload`; the routing fields (`topic`, `event_type`, `aggregate_id`, `tenant_id`, `schema_version`, `correlation_id`) are real columns beside it.
2. **Change Data Capture:** There is no polling relay. Debezium tails the PostgreSQL write-ahead log through a logical replication slot (`order_outbox_slot`) and reads each inserted row.
3. **Kafka Transmission:** Debezium's outbox event router selects the target topic from the row's `topic` column (managed by `MessagingTopics.java`), uses `aggregate_id` as the record key, and maps the metadata columns onto the CloudEvents `ce_*` headers.
4. **Broker Acknowledgment:** The connector commits its offset only after the broker acknowledges the produce. The WAL is the record of what was emitted, so the application keeps no `published_at`/`attempts` bookkeeping.
5. **Event Consumption:** The `notification-service` is listening to the Kafka stream via its `OrderEventListener.java`. It grabs the event body, processes the inner payload parameters, and handles multi-channel user alerts (e.g., executing email workflows advising the client that their payment method failed).

---

## 3. Comprehensive Module & Package Inventory

Here is the exact mapping of your current module implementations and how they function together logically:

### Core Configuration & Reuse Module: `common-bom`
This module contains standard configurations to prevent code repetition across distinct domains.
* **`common-security`**: Tracks multi-tenant user properties. It maps user request contexts, handles authorization scopes, and utilizes `ProblemDetailAccessDeniedHandler.java` to output error codes following the **RFC-7807 Standard Format**.
* **`common-messaging`**: Configures central producer pipelines. It structures cluster connection retries, provisions serialization layouts, and ensures headers flow correctly into target clusters.
* **`common-observability`**: Implements global filters to intercept incoming requests and inject tracking correlation hashes into logging metrics and distributed tracing spans.

### Transactional Engine Components: `core-services`
* **`order-service`**: Houses the main checkout engines, saga orchestration tracking frameworks, and the database outbox persistence mechanisms.
* **`customer-service`**: Stores profile structures, address models, and operational flags across tenant systems.
* **`payment-service`**: Isolates gateway communication code, maps response tracking fields, and runs security sweeps via `PaymentGatewaySafetyCheck.java`.

### High-Throughput Buffers: `transactional-caches`
* **`inventory-service`**: Manages real-time stock validations, processes lock limits, and clears expired stock requests via a background daemon (`ReservationExpirySweeper.java`).
* **`shopping-cart-service`**: A low-latency state machine designed to track session data, compute pricing totals, and handle quick user actions.

### Search & Storefront Modules: `catalog-services`
* **`product-catalog-service`**: Handles item data searches, categorization mappings, and variations.
* **`review-rating-service`**: Tracks score reviews, filters text metrics, and calculates average star scores per product.

---

## 4. Key Architectural Patterns Decoded

When discussing this repository with tech leads or interviewers, lean on these technical definitions to explain your architecture choices:

### Orchestrator-Based Saga Pattern
* **The Choice:** Choreographed sagas (where services use event listeners to trigger workflows) can create complex cyclical dependencies that are hard to trace. 
* **The Architecture:** This design utilizes a centralized Orchestrator inside the `order-service`. The orchestrator explicitly directs the business steps, handles service rollbacks, and serves as a single source of truth for tracking active workflow transactions.

### Transactional Outbox Pattern
* **The Choice:** Directly broadcasting to message brokers mid-transaction creates an anti-pattern. If the network drops or Kafka experiences a brief hiccup after a database save, your system splits into an inconsistent state.
* **The Architecture:** By committing the event payload straight into the local database within the same ACID transaction, we guarantee event capture. Delivery is then Change Data Capture — Debezium streams the write-ahead log to Kafka — which gives **At-Least-Once Delivery** to downstream consumers without a polling relay.

### Multi-Tenant Header Forwarding Context
* **The Choice:** Avoid mixing tenant checking filters within individual service endpoints.
* **The Architecture:** The system handles tenancy checks right at the edge gateway. Once verified, the tenant metadata is injected into the request header pipeline. Downstream microservices process this attribute to handle row-level query filtering or route traffic to tenant-specific databases.

---

## 5. Production Optimization Roadmap

Before you push this code to a production environment, incorporate these optimization strategies into your engineering backlog:

1. **Already Done — Change Data Capture (CDC):** The repository no longer polls the outbox table; Debezium streams changes directly from the database write-ahead log (WAL), eliminating continuous `SELECT` queries and freeing up database performance. The remaining hardening step is operational: run Debezium on a managed Kafka Connect worker with health checks, and alert on replication-slot lag.
2. **Implement Resilient Cluster Fallbacks:** Configure a dedicated **Dead Letter Topic (DLT)** for your Kafka cluster consumers. If a worker fails to process an event multiple times, it pushes it to the DLT for manual review instead of blocking the main messaging stream.
3. **Add Global Redis Layering:** Add a second Redis layer to your `product-catalog-service` using a **Cache-Aside Pattern**. This caches popular product details to drastically reduce read pressure on your primary relational databases during peak traffic events.
