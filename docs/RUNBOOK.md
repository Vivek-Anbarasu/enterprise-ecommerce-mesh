# Operations Runbook

Alert-driven procedures. Each entry states the alert, how to confirm it, the fix,
and how to verify recovery.

## Severity model

| Level | Meaning | Response |
|---|---|---|
| `critical` | Lost revenue, data loss, or full outage | Page immediately |
| `warning` | Degradation with a workaround, or rising risk | Handle during business hours |

Alerts that merely report a metric moving are noise and get muted, which trains
people to ignore pages. Every rule in `platform/observability/prometheus/rules/slo.yml`
is written to be actionable without further investigation.

---

## Checkout

<a id="checkout"></a>
### CheckoutConversionCollapse

**Symptom:** checkout success rate below 80% for 15 minutes. This is lost
revenue, not merely an error rate, and it is the alert that matters most.

```promql
checkout:success_ratio:rate5m
```

**Triage:**

1. Split by outcome. `mesh_business_events_total{domain="orders",outcome="..."}`
   tells you whether failures are stock rejections or payment declines.
2. Stock rejections climbing → inventory is out of sync. Check whether a recent
   deploy broke the reservation path:
   ```bash
   kubectl -n ecommerce logs -l app.kubernetes.io/name=inventory-service --tail=200
   ```
3. Payment declines climbing → check the provider directly. Do **not** retry
   blindly; a decline may be legitimate fraud filtering.

**Stock rejection spike fix.** Usually reservations are being created but never
confirmed or released, so availability is silently consumed:

```sql
-- Holds past their TTL that the sweeper has not reclaimed.
SELECT reservation_id, sku, quantity, expires_at
FROM inventory_reservations
WHERE status = 'PENDING' AND expires_at < now() - interval '10 minutes';
```

If that returns rows, the sweeper is not running. Check
`mesh.inventory.sweeper.enabled` and the inventory pod logs.

**Verify:** the success ratio returns above 97% and the rejection rate falls.

---

<a id="outbox"></a>
### OutboxBacklogGrowing

**Symptom:** unpaid order events accumulating. Paid orders exist that fulfilment
has never heard about.

```promql
outbox_unpublished_total
```

This is the alert that catches a broken relay. Because the outbox is written in
the same transaction as the order, a backlog means the *relay* is broken, not
that orders are being lost.

**Triage:**

```bash
kubectl -n ecommerce logs -l app.kubernetes.io/name=order-service \
  --tail=500 | grep -i outbox
```

Common causes, in order of likelihood:

1. **Kafka unreachable.** The relay logs a publish failure per event. Check
   connectivity and broker health.
2. **Relay disabled.** `mesh.outbox.relay-enabled` set to false, often by an
   operator debugging something else and never reverted.
3. **Event payload too large.** Serialisation failures exceed
   `MAX_PAYLOAD_BYTES` and can never succeed on retry.

**Recovery.** Once Kafka is healthy the relay drains the backlog on its own at
`mesh.outbox.batch-size` per second. Watch it fall:

```promql
outbox_unpublished_total
```

**Replaying dead-lettered events.** Events past `mesh.outbox.max-attempts` are
deliberately abandoned so they cannot starve healthy traffic. They remain in the
table. To replay after fixing the root cause:

```sql
-- Inspect first.
SELECT id, event_type, aggregate_id, attempts, last_error
FROM outbox_events
WHERE published_at IS NULL ORDER BY created_at LIMIT 50;

-- Reset only the ones you have diagnosed.
UPDATE outbox_events SET attempts = 0, last_error = NULL
WHERE id IN ('...') AND published_at IS NULL;
```

The relay picks them up on its next poll.

---

## Availability

### ServiceHighErrorRate

**Symptom:** 5xx ratio above 5% for 10 minutes.

```promql
service:error_ratio:rate5m
```

**Triage:**

```bash
# What changed?
kubectl -n ecommerce rollout history deployment/<service>
kubectl -n ecommerce get events --sort-by=.lastTimestamp | tail -20
```

If a rollout lines up with the onset, roll back first and diagnose afterwards.
A rollback is faster and safer than a live fix under customer impact:

```bash
helm rollback ecommerce <previous-revision>
```

Otherwise walk the dependency chain. Circuit breakers in the gateway mean an
upstream failure surfaces as 503 with a `ProblemDetail` body naming the
dependency, so start there.

**Verify:** `service:error_ratio:rate5m` below 1% for 10 minutes.

### NoHealthyReplicas

A Deployment has zero available replicas. Check, in order:

```bash
kubectl -n ecommerce get pods -l app.kubernetes.io/name=<service>
kubectl -n ecommerce describe pod <pod>
```

Common causes: image pull failure (ACR attach or workload identity), failing
readiness probe (the probe is checking a dependency that is also down), or a
`ResourceQuota` rejection.

**Note on readiness probes.** They are configured with a dependency group, so a
database outage marks every replica unready at once. That is deliberate: it
removes the pod from the Service rather than serving errors. Confirm the Service
has endpoints before concluding the app is broken:

```bash
kubectl -n ecommerce get endpoints <service>
```

---

## Kafka

### KafkaConsumerLagHigh

**Symptom:** notifications or fulfilment falling behind real time.

```promql
kafka_consumer_fetch_manager_records_lag
```

**Triage.** Check whether consumers are simply too few:

```bash
kubectl -n ecommerce get pods -l app.kubernetes.io/name=notification-service
```

Consumer concurrency is capped by partition count, so adding replicas past that
count produces idle pods and no extra throughput. Check the topic:

```bash
kubectl -n ecommerce exec statefulset/kafka -- kafka-topics.sh \
  --bootstrap-server localhost:9092 --describe --topic order.events
```

If partitions < consumers, add partitions. If partitions are plentiful, scale
consumers and look for a slow handler.

**Poison message suspicion.** If one pod is stuck while others are fine, suspect a
poison message. Check the DLQ:

```bash
kubectl -n ecommerce exec statefulset/kafka -- kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic order.events.dlq --from-beginning --max-messages 5
```

The retry policy diverts to `.dlq` after `mesh.messaging.max-retries`, so the
partition keeps moving and only that message is quarantined.

### DeadLetterQueueFilling

Messages are failing repeatedly. Inspect the DLQ, fix the consumer, then replay
into the source topic with the original event ids intact so consumer-side
idempotency still suppresses the work already done.

---

## Platform

### PodCrashLooping

```bash
kubectl -n ecommerce logs -l app.kubernetes.io/name=<service> --previous --tail=100
```

`--previous` is essential; the current container's log is empty because it never
started.

Common causes: `OutOfMemoryError` (raise the memory limit before anything else),
a configuration error at startup, or a dependency that must exist before the pod
can boot.

### MemoryPressure

Memory requests close to limits. Before raising the limit, check for a leak:

```promql
jvm_memory_used_bytes{area="heap"}
```

If used heap grows monotonically without plateauing, that is a leak and raising
the limit only delays the OOM. Services use virtual threads, so a large thread
count is not the explanation.

---

## Routine tasks

**Rotating the JWT signing key.** Write the new public key to Key Vault, then
roll the pods:

```bash
kubectl -n ecommerce rollout restart deployment -l app.kubernetes.io/part-of=enterprise-ecommerce-mesh
```

Order matters: deploy the new key where both old and new are accepted *before*
switching the issuer to sign with it.

**Scaling a non-critical tier.** The AI tier is the usual candidate:

```bash
helm upgrade ecommerce deploy/helm/enterprise-ecommerce \
  -f deploy/helm/values-prod.yaml \
  --set services.ai-orchestrator-service.enabled=false
```

The gateway circuit-breaks and returns 503 with a `fallback` marker, and the
storefront shows trending products instead of recommendations.

**Adding partitions.** Increasing partition count is irreversible and changes
partition-key hashing, so in-flight ordering guarantees shift. Do it during a
quiet period and expect temporary rebalance lag.
