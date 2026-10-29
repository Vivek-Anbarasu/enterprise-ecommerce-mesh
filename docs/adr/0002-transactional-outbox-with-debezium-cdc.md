# ADR-0002: Events leave through a transactional outbox read by Debezium

- **Status:** Accepted
- **Date:** 2026-10-29
- **Scope:** `order-service`, `product-catalog-service`, Debezium, Kafka

## Context

When an order is committed, downstream services must learn about it: `notification-service`
sends the confirmation, `shipping-delivery-service` starts the shipment, and the AI service
keeps its index in step. The state change and the event have to agree.

There are two ways to get this wrong, and both are silent:

- **Write the row, then publish to Kafka.** If the process dies between the two, the order
  exists and no consumer ever hears about it.
- **Publish first, then write.** If the commit fails, consumers act on an order that does
  not exist.

A dual write across two systems cannot be made atomic without a distributed transaction,
and this system deliberately does not have one (see ADR-0001).

This repository previously used a **polling relay**: a scheduled job selected unpublished
rows, published them, and marked them done. That design was removed. Its bookkeeping
columns (`published_at`, `attempts`, `last_error`) and its partial index existed only to
serve the relay's own hot query, and they made the table's meaning ambiguous — after the
switch to CDC, a query for "what is still unpublished?" would have matched the entire table
forever. The `V2` migration drops them.

## Decision

Use a **transactional outbox** whose contents are published by **change data capture**.

- The outbox row is written **inside the transaction that writes the aggregate**.
  `OutboxService.record(...)` is `Propagation.MANDATORY`, so it cannot be called outside a
  transaction and a failure to write the row aborts the whole order transaction rather than
  being swallowed — which is what prevents an order existing with no corresponding event.
  The one deliberate exception is `recordAfterRollback(...)`, which uses `REQUIRES_NEW`
  because a failure event must survive the rollback of the transaction that failed.
- **Debezium** tails the PostgreSQL write-ahead log using the outbox event router and
  publishes each inserted row to `order.events`. Nothing in the application reads the
  outbox back, and nothing updates it after insert.
- **The `payload` column is a contract.** It must be the bare business event, because the
  router publishes that column verbatim as the Kafka record value and every consumer reads
  the record value as the event. Routing and metadata travel in sibling columns that the
  router maps onto the `ce_*` headers. Wrapping the payload in another JSON envelope would
  break every consumer at once, and silently — a consumer handed the wrong shape finds its
  fields missing rather than being told it is wrong.
- **Payloads are bounded** at 256 KB so a bug cannot enqueue an unbounded record.
- **Delivery is at-least-once.** Consumers must be idempotent on the event id.

The same pattern is used by `product-catalog-service` for `product.events`, which keeps
the semantic-search vector index in step with the catalogue.

## Consequences

### Positive

- The commit and the event are the same fact. There is no window in which one exists
  without the other.
- No relay process, no polling interval, no "unpublished" backlog query, and no bookkeeping
  columns whose meaning decays.
- Publication latency is WAL-to-Kafka, not poll-interval.
- Replays are safe: the outbox is append-only and the broker retains the stream.

### Negative

- **Debezium becomes an operational dependency.** If it stops, events stop. Its lag is a
  metric that must be alerted on, not inferred.
- The payload shape is now a wire contract; changing it is a coordinated change across
  producers and consumers.
- WAL retention must be long enough to survive a Debezium outage, or the connector loses
  its position and needs a re-snapshot.
- Consumers see duplicates on replay and must deduplicate on the event id.

### Operational

- Connector configuration lives in `deploy/kubernetes/debezium/order-outbox-connector.yaml`.
- The replication slot name must stay stable across restarts: it is how the connector
  resumes, and changing it silently starts a new stream from the current WAL position,
  dropping everything emitted while the connector was down.
- `tasksMax: 1` is deliberate. Partition-level ordering within an aggregate is the reason
  the event key is the aggregate id; a second task could deliver one order's events out of
  order.
- The publication is auto-created in `filtered` mode and the connector is restricted to
  `public.outbox_events`; without that restriction the router would fail on every
  non-outbox row.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Dual write** (commit, then publish in the same method) | Not atomic. Either an order exists with no event, or an event exists for an order that rolled back. Both are silent until someone reconciles. |
| **Polling relay** (the previous design) | Adds a poll interval of latency, needs claim/mark/retry bookkeeping, needs a partial index over unpublished rows, and makes "is the event out?" a query against a table that is being mutated by the publisher. Removed in the `V2` migration. |
| **Listen to yourself** (publish, then consume your own topic to confirm) | Confirms delivery, not atomicity. The commit can still fail after the publish. |
| **Kafka transactions** | Ties the database transaction to the broker transaction and still does not make a PostgreSQL commit and a Kafka commit atomic across the boundary. |

## Revisit triggers

- Debezium lag breaches the event-delivery SLO for a sustained period.
- The platform moves off PostgreSQL, or to a broker that can genuinely participate in the
  database transaction.
- Event volume or payload size outgrows the outbox table's retention policy.
- A consumer needs strict global ordering, which per-aggregate keying does not provide.

## References

- `core-services/order-service/src/main/java/com/enterprise/mesh/order/saga/OutboxService.java`
- `core-services/order-service/src/main/java/com/enterprise/mesh/order/persistence/OutboxEvent.java`
- `core-services/order-service/src/main/resources/db/migration/V1__order_schema.sql`
- `core-services/order-service/src/main/resources/db/migration/V2__outbox_cdc.sql`
- `deploy/kubernetes/debezium/order-outbox-connector.yaml`
- `common-bom/common-messaging/src/main/java/com/enterprise/mesh/messaging/`
