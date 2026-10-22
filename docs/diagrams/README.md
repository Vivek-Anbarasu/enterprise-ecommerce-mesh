# Architecture Diagrams

## Main HLD

Editable multi-page draw.io view of the production topology. Page 1 is deliberately three lanes:

1. **Azure edge and identity** — client, Application Gateway for Containers, Entra ID.
2. **AKS cluster** — istiod, the shared ambient data plane (`ztunnel`, waypoint), the two edge workloads, the nine waypoint-enrolled services, and the L4-only Kafka consumer.
3. **Managed Azure services** — PostgreSQL, Cosmos DB for MongoDB, Managed Redis, Service Bus, Key Vault, plus an observability hand-off to page 2.

What the diagram makes explicit, and the earlier hand-drawn version did not:

- **12 services**, not 11. `bff-service` is a real edge workload on `:8091`.
- **9 waypoint badges**, one on every enrolled service. `bff-service`, `api-gateway` and `notification-service` are not enrolled.
- **Data lives outside the cluster.** Managed Redis is `:6380`, not an in-cluster `redis-cache` Service; PostgreSQL, Cosmos DB, Service Bus and Key Vault are all in the managed lane.
- **The async path is drawn.** `order-service` → outbox → Debezium → `order.events` → notification and shipping.
- **The edge is plaintext on two ports only.** `:8080` and `:8091` are PERMISSIVE because AGC has no SPIFFE identity; everything else is STRICT.
- **Observability is a one-box hand-off to page 2**; the full architecture is the second page of the same file, not a second full topology on page 1.

### Files

| File | Purpose |
|---|---|
| [`hld-mesh-architecture.drawio`](hld-mesh-architecture.drawio) | **Editable source.** Page 1 is the main HLD; page 2 is the observability deep dive. Open in [diagrams.net](https://app.diagrams.net) or the draw.io desktop app |

Page 1 is 2080 × 1680; page 2 is 1800 × 1250. All boxes, lanes, notes and connectors are native draw.io cells, not a flattened image. The repository keeps `.drawio` sources only; no PNG or SVG exports.

The companion written description is [`../HLD-MESH-DIAGRAM.md`](../HLD-MESH-DIAGRAM.md).

---

## Full call graph — sync and async

Editable presentation companion to the main HLD. It separates the two interaction
styles so each can be explained on its own:

1. **Synchronous HTTP** — browser, mobile and partner clients; AGC; BFF; gateway;
   the ten gateway routes; the three service-to-service calls; and the external
   provider adapters.
2. **Asynchronous events** — the order outbox, Debezium, `order.events`,
   `product.events`, the four consumer groups, and the per-topic DLQs.

The service names appear twice on purpose: once in the API role and once in the
async producer or consumer role. State stores are shown in the node captions,
not as call edges, so the diagram stays a call graph rather than a data map.

### Files

| File | Purpose |
|---|---|
| [`full-call-graph.drawio`](full-call-graph.drawio) | **Editable source.** Open in [diagrams.net](https://app.diagrams.net) or the draw.io desktop app |

The page is 2800 × 2200 and uses native draw.io cells and connectors. It is
deliberately separate from the main HLD so it can be presented without changing
the production topology view. The edge-by-edge companion is
[`../FULL-CALL-GRAPH.md`](../FULL-CALL-GRAPH.md).

---

## Observability deep dive

This view is also **page 2 of `hld-mesh-architecture.drawio`**, so it can be presented back-to-back with the main HLD. The standalone file below remains the editable deep-dive source; keep the two copies in sync when editing.

Corrected HLD view with three lanes:

1. **AKS cluster** — application instrumentation and Azure-managed add-ons.
2. **Azure managed observability** — metrics, traces, and logs stores plus Grafana.
3. **Azure Monitor alerting** — Prometheus rule groups, action groups, on-call, and the optional ACS bridge.

Key corrections from the earlier diagram:

- Micrometer belongs with Actuator metrics, not tracing.
- The production diagram has no Jaeger; traces go through an in-cluster OTel Collector to Application Insights.
- The collector is the credential bridge: only it holds the Azure OTLP authorization header.
- Logs flow through Container Insights to Log Analytics; App Insights is for traces only.
- Azure Monitor Prometheus rule groups and action groups are the alert path, not Grafana-managed alerts.
- ACS Email is optional and requires a Logic App/Function bridge.

### Files

| File | Purpose |
|---|---|
| [`observability-architecture.drawio`](observability-architecture.drawio) | **Editable deep-dive source.** Open in [diagrams.net](https://app.diagrams.net) or the draw.io desktop app |

To edit, open `observability-architecture.drawio` in diagrams.net (File → Open From → Device) or the desktop app. The page is 1800 × 1250; all boxes, lanes, notes, and connectors are native draw.io cells, not a flattened image.
