# ADR-0001: Checkout is a compensatable orchestrator saga

- **Status:** Accepted
- **Date:** 2026-10-29
- **Scope:** `order-service`, `inventory-service`, `payment-service`

## Context

Checkout is the one flow in this system where a shopper is actively waiting on the
response. It spans three services that each own a separate database: stock is held in
`inventory-service`, money is authorised in `payment-service`, and the order is committed
in `order-service`. There is no distributed transaction available, and there cannot be one:
two-phase commit would require every participant to hold locks across the network, so the
availability of checkout would become the product of the availability of three services.

The legacy monolith ran the whole thing in a single ACID transaction. That is not
available here, and pretending otherwise produces the worst outcome — a payment taken for
an order that was never written.

The failure modes that matter are not the happy path:

- stock is held, then the payment is declined;
- payment is authorised, then the order transaction fails to commit;
- a participant hangs, holding the shopper's request open;
- the orchestrating pod dies between two remote calls;
- the client retries after a timeout and the first attempt was actually still running.

## Decision

Checkout is a **compensatable orchestrator saga** owned by `order-service`, driven by
`CheckoutSagaOrchestrator` rather than by messages.

Steps, in order: **reserve stock → authorise payment → confirm the hold → capture the
payment → commit the order and emit its event.** A failure unwinds only as far as the saga
actually got.

The properties that make it safe:

- **Durability before action.** The order is written as `PENDING` before the first remote
  call, and each step advances a persisted `SagaStep` through `SagaStateStore`. If the
  process dies, the state is still there.
- **Lease-based claims.** A saga is claimed with a lease (`mesh.saga.claim-lease`, two
  minutes by default) before it is attempted, so a second replica cannot resume a
  checkout that is still in flight.
- **Bounded budgets.** Connect and read timeouts live in `SagaProperties`, not in code
  (`2s` / `5s`). A checkout step that takes longer than a few seconds has already lost the
  shopper; the circuit breaker, not a long timeout, is what absorbs a slow dependency.
- **Durable compensation.** A failed checkout enqueues `CompensationAction` rows in
  PostgreSQL. `SagaCompensationWorker` retries them with exponential backoff, so a payment
  service that is down for five minutes is refunded when it returns rather than written
  off. "Compensate" means "queue a durable intent and try now", not "call once and hope".
- **Recovery.** `StaleSagaSweeper` fails checkouts whose lease expired mid-flight and
  queues the undo implied by the step they reached.
- **Idempotency.** A unique index on `(tenant_id, idempotency_key)` makes a retry return
  the stored order instead of running the steps twice.
- **No transaction across a remote call.** The orchestrator is deliberately **not**
  `@Transactional`; each local write is its own short transaction. `SagaTransactionBoundaryTest`
  asserts both halves by reflection, because re-annotating `placeOrder` would silently
  restore the connection-and-row-lock window around two HTTP calls and make the connection
  pool the ceiling on concurrent checkouts.

## Consequences

### Positive

- The state of every in-flight checkout is queryable, not inferred from a log stream.
- A crashed orchestrator is recoverable; a hung dependency is bounded.
- Compensation is auditable. `inventory-service` records `UNCONFIRMED` as a distinct
  terminal state from `RELEASED`, so an operator can tell "hold returned" apart from
  "confirmed then rolled back".
- A `409 Sold out` from inventory is treated as a legitimate business answer, not a
  failure, and does not count toward the breaker's error rate.

### Negative

- The orchestrator is a coupling point: it knows the order of every step, so adding a
  participant means changing it.
- Every checkout writes saga state in addition to the order, which is more database work
  than the monolith did.
- The sweeper is another moving part that must be correct; a bad lease value either
  sweeps live checkouts or holds stock too long.
- **A timeout on authorise is genuinely ambiguous.** The provider may have committed the
  hold even though this process never saw the reference. The order is failed and the stock
  released, and the orphaned hold is left to the provider's authorisation window. Making
  that airtight needs a reconciliation job against the provider, not a different
  orchestration mode.

### Operational

- Compensation backlog and stale-saga sweeps are business metrics, not HTTP status codes.
- The claim lease must exceed the worst case of four read timeouts plus local work.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Choreography** (each service reacts to events) | There is no single place that knows whether a checkout succeeded, and nothing to compensate against when a step fails. Debugging a failed checkout means reconstructing a timeline across four topics. It also makes the shopper's synchronous result an event to poll for. |
| **Two-phase commit / XA** | Requires participants to hold locks across the network. Checkout availability becomes the product of three services' availability, and the managed data stores in this stack do not support it cleanly. |
| **One long database transaction** | This was the actual defect the split fixed: it held a connection and the order's row lock for the duration of two HTTP round trips, making the connection pool the ceiling on concurrent checkouts. |
| **Best-effort compensation** (call once, log on failure) | A five-minute payment outage becomes a permanent inconsistency. This is the failure the compensation queue exists to prevent. |

## Revisit triggers

- More than six participants, or steps that span hours rather than seconds.
- Checkout becomes asynchronous (bulk or B2B ordering), where choreography's decoupling
  is worth more than the orchestrator's visibility.
- The payment provider exposes an idempotent lookup by reference, which would make the
  ambiguous-timeout case recoverable and remove the last known inconsistency.

## References

- `core-services/order-service/src/main/java/com/enterprise/mesh/order/saga/CheckoutSagaOrchestrator.java`
- `core-services/order-service/src/main/java/com/enterprise/mesh/order/saga/SagaStateStore.java`
- `core-services/order-service/src/main/java/com/enterprise/mesh/order/saga/SagaCompensationWorker.java`
- `core-services/order-service/src/main/java/com/enterprise/mesh/order/saga/StaleSagaSweeper.java`
- `core-services/order-service/src/main/java/com/enterprise/mesh/order/saga/SagaProperties.java`
- `core-services/order-service/src/test/java/com/enterprise/mesh/order/saga/SagaTransactionBoundaryTest.java`
- `transactional-caches/inventory-service/src/main/resources/db/migration/V2__reservation_unconfirm.sql`
