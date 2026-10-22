# Operations Runbook

Alert-driven procedures. Each entry states the alert, how to confirm it, the fix,
and how to verify recovery.

## Severity model

| Level | Meaning | Response |
|---|---|---|
| `critical` | Lost revenue, data loss, or full outage | Page immediately |
| `warning` | Degradation with a workaround, or rising risk | Handle during business hours |

For the full P0/P1/P2 catalog with notification routing and implementation
status, see [`docs/ALERT-CATALOG.md`](ALERT-CATALOG.md).

Alerts that merely report a metric moving are noise and get muted, which trains
people to ignore pages. Every rule in `platform/observability/amp/rules.yaml`
is written to be actionable without further investigation.

---

## What this runbook does not cover

Recorded so the gaps are not mistaken for an oversight, and so nobody adds a rule
back that the platform cannot evaluate.

<a id="platform-alerting"></a>
### Platform and Kubernetes alerting

The reference catalogue at `platform/observability/amp/rules.yaml` lists
`PodCrashLooping`, `NoHealthyReplicas` and `MemoryPressureHigh`. They were absent
while the self-hosted Prometheus was in use, because nothing produced the
`kube_*` series they evaluate.

They are present again because Azure Monitor managed Prometheus deploys
`ama-metrics-ksm`, which supplies those series.

> `MemoryPressureHigh` is not the rule that was removed. The old expression
> computed `limits / requests`, which is a deployment-time constant — it changes
> only when someone edits a Helm value, and could not have detected runtime
> pressure even with the metrics present. The replacement compares
> `container_memory_working_set_bytes` against the limit, which is the signal
> that actually precedes an OOM kill.

<a id="alert-delivery"></a>
### Alerting delivery is a Bicep deployment, not a kubectl apply

Azure Monitor managed Prometheus does **not** evaluate `PrometheusRule` custom
resources. The deployable rules are a
`Microsoft.AlertsManagement/prometheusRuleGroups` resource in
[`deploy/bicep/alerting.bicep`](../deploy/bicep/alerting.bicep), with critical
and warning action groups attached.

Until that deployment has run, every rule in this runbook is a specification of
what *should* alert, not a description of what currently does. Deploy it with
the command in [`deploy/azure/README.md`](../deploy/azure/README.md#5-action-groups-and-prometheus-rule-groups).

A rule that evaluates but has no action group is the same as no rule at all.
`rules.yaml` remains the readable catalogue; it is deliberately excluded from
`platform/observability/amp/kustomization.yaml` so it cannot be mistaken for a
deployed object.

<a id="log-correlation"></a>
### Trace IDs resolve in the trace backend; joining a trace to logs does not yet

Logs are structured to ECS and MDC carries `request_id`, `traceId` and
`tenant_id`.

Azure Monitor managed Prometheus does **not** store traces, and neither does
Managed Grafana. Traces go to Application Insights through the in-cluster
OpenTelemetry Collector (`platform/observability/otel-collector/`) — see
[`deploy/azure/README.md`](../deploy/azure/README.md). The collector terminates
OTLP from both the applications and the Istio waypoint and is the only workload
that holds the ingestion credential; neither the services nor istiod need it.

Grafana's `tracesToLogsV2` is correspondingly unconfigured. It previously pointed
at Prometheus, a metrics-only backend, which Grafana accepts and then silently
ignores.

Every RFC 9457 problem body carries the active `traceId`, so a reported error
gives you a trace id directly. Two things to expect when you search for it:

- **It may not be there.** Sampling is 10% on request paths and the root span
  decides, so most requests produce no trace. A missing trace is usually
  sampling, not a broken exporter — check for the waypoint span (see below) to
  tell the two apart.
- **The application half may be absent.** The waypoint samples independently. A
  short trace rooted at the waypoint with no application parent is expected noise,
  not corruption.

A payment decline returns **HTTP 200** with `status=FAILED`, so every span in
that trace reads 200. Diagnose from span tags or
`mesh_business_events_total{domain="orders",outcome="..."}`, never from span
status.

Trace context does not cross the Kafka boundary. `X-Request-ID` (HTTP) and
`ce_correlationid` (Kafka) are separate values and are not guaranteed to match, so
a query joining a shopper's click to its fulfilment event needs both fields, and
the two trace trees have to be joined by hand.

Mesh spans come from the waypoint only. ztunnel emits none, so the L4 hop is
invisible in the trace backend; `api-gateway`, `bff-service` and
`notification-service` have no waypoint either. See
[`deploy/istiod/README.md`](../deploy/istiod/README.md).

### Ambient mode does not merge proxy metrics

There is no sidecar to merge proxy metrics into an application's
`/actuator/prometheus`. `platform/observability/amp/podmonitors.yaml` therefore
declares the waypoint as its own scrape target, and waypoint series carry
`reporter="waypoint"` where sidecar mode used `reporter="destination"`.

A dashboard written against sidecar mode selects nothing after the move to
ambient, with no error. `deploy/azure/README.md` flags the dashboard migration
for exactly this reason.

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
### OrderEventsNotReachingConsumers

**Symptom:** order events accumulating. Paid orders exist that fulfilment has
never heard about.

There is no application-side metric for this any more. The polling relay that
owned `outbox_unpublished_total` has been replaced by Debezium streaming the
PostgreSQL write-ahead log, so CDC lag is a Kafka Connect / Debezium metric, not
something order-service exposes. This section describes what to check instead.

Because the outbox row is written in the same transaction as the order, an event
that is missing downstream means the *pipeline* is broken, not that the order
was lost. The row is durable either way.

**Triage:**

```bash
# Is the row even there? If not, the order never committed an event.
psql -h <order-db-host> -c \
  "SELECT id, event_type, aggregate_id, created_at
   FROM outbox_events ORDER BY created_at DESC LIMIT 20;"

# Is Debezium streaming? Check the connector task status.
kubectl -n ecommerce exec deploy/kafka-connect -- \
  curl -s localhost:8083/connectors/<debezium-connector>/status | jq '.tasks'
```

Common causes, in order of likelihood:

1. **Connector stopped or never started.** A `FAILED` task status here is the
   usual cause. The order-service logs are irrelevant here - it only ever inserts.
2. **`wal_level` is not `logical`.** CDC requires it. If the setting changed the
   connector cannot read the WAL at all.
3. **Kafka or the connector's broker unreachable.** Check connector health and
   broker connectivity.
4. **Event payload too large.** Serialisation failures exceed
   `MAX_PAYLOAD_BYTES` in `OutboxService` and fail the order transaction, so
   these surface as failed orders rather than missing events.

**Recovering a stopped connector.** Debezium resumes from its last committed
offset once the task is restarted; there is no backlog in the application to
drain. Do not reset or replay rows in `outbox_events` - the table is
append-only, and re-inserting a row emits a duplicate event. Consumers are
idempotent on `event_id`, so an at-least-once redelivery is safe; a
hand-rolled duplicate is not.

**Note on unbounded growth.** With CDC nothing deletes old rows, so
`outbox_events` grows forever. Pruning it is a separate operational job - do not
delete rows inside a retention window that Debezium has not yet streamed.

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
