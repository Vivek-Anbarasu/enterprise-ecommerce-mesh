# Full Call Graph — Sync and Async

Presentation companion to the main HLD. The editable diagram is
[`diagrams/full-call-graph.drawio`](diagrams/full-call-graph.drawio).

The main HLD answers **what is deployed**. This view answers **who calls whom**.
It is deliberately separate so the two interaction styles can be shown on their
own:

- **Synchronous HTTP** — clients, AGC, BFF, gateway, service-to-service calls,
  and external provider adapters.
- **Asynchronous events** — the order outbox, Debezium, Kafka topics, consumer
  groups, and DLQs.

State stores are in the node captions, not drawn as call edges. The full
production topology and data-store placement remain in the main HLD.

---

## 1. Synchronous edges

### Edge and identity

| From | To | Label | Meaning |
|---|---|---|---|
| Browser | AGC | HTTPS · session cookie | The browser holds only the HttpOnly session cookie. |
| Mobile app | AGC | HTTPS · bearer token | Mobile acquires a token directly from Entra, then calls the gateway through AGC. |
| Partner | AGC | HTTPS · bearer token | Partner uses `client_credentials`; it gets an access token, not a refresh token. |
| Mobile app | Entra ID | `authorization_code + PKCE` | Public client; token stored in Keychain / Keystore on the device. |
| Partner | Entra ID | `client_credentials` | Confidential client; service credential. |
| BFF | Entra ID | code exchange + refresh | The BFF is the browser's OAuth client and holds the refresh token. |
| Gateway | Entra ID | JWKS / OIDC discovery | JWT signature keys; not a per-request business call. |
| AGC | BFF | `/bff/**` + `/login` | Browser-facing surface. |
| AGC | Gateway | `/**` | Mobile, partner, and direct API traffic. |
| BFF | Gateway | bearer token + `X-Tenant-ID` | The BFF has no direct service clients; all aggregation goes through the gateway. |

### Gateway routes

The gateway has ten business routes plus the internal docs route:

| Path | Target |
|---|---|
| `/api/products/**`, `/api/categories/**` | `product-catalog-service:8085` |
| `/api/reviews/**` | `review-rating-service:8088` |
| `/api/cart/**`, `/api/carts/**` | `shopping-cart-service:8086` |
| `/api/customers/**` | `customer-service:8087` |
| `/api/orders/**` | `order-service:8082` |
| `/api/payments/**` | `payment-service:8084` |
| `/api/inventory/**` | `inventory-service:8083` |
| `/api/ai/**` | `ai-orchestrator-service:8090` |
| `/internal/shipping/**` | `shipping-delivery-service:8089` |
| `/internal/notifications/**` | `notification-service:8081` — actuator only |
| `/internal/docs/**` | swagger-ui — tooling route, not drawn |

### Service-to-service calls

There are only three synchronous service-to-service calls in the repo:

| From | To | Label | Notes |
|---|---|---|---|
| `order-service` | `inventory-service` | reserve · confirm · release · unconfirm | RestClient, `X-Tenant-ID`, no bearer token. |
| `order-service` | `payment-service` | authorise · capture · void | RestClient, `X-Tenant-ID`, no bearer token. |
| `ai-orchestrator-service` | `product-catalog-service` | GET `/api/products`, `/api/products/sku/{sku}` | Category and candidate lookup for recommendations. |

### External adapters

| From | To | Label | Status |
|---|---|---|---|
| `payment-service` | Payment provider API | authorise · capture · refund | Real HTTP adapter only when `PAYMENT_GATEWAY=http`. `void` changes local status only; it does not call the provider. |
| `shipping-delivery-service` | Carrier API | purchaseLabel · cancelLabel | `CarrierGateway` seam; the only implementation in this repo is `StubCarrierGateway`. |
| `notification-service` | — | — | No external vendor: the dispatcher logs instead of sending. |

---

## 2. Asynchronous edges

### Producers

| From | To | Label | Notes |
|---|---|---|---|
| `order-service` | `outbox_events` | INSERT in the order transaction | No direct Kafka publish from `order-service`. |
| `outbox_events` | Debezium | PostgreSQL WAL → EventRouter | `route.by.field=topic`; the Kafka Connect cluster is not in this repo. |
| Debezium | `order.events` | CDC EventRouter | Key is the aggregate id; CloudEvents headers are mapped from outbox columns. |
| `product-catalog-service` | `product.events` | publish AFTER_COMMIT | Not an outbox; a lost product event leaves one stale vector, which a reindex can rebuild. |

### Consumers

| Topic | Consumer | Group | Filter / behaviour |
|---|---|---|---|
| `order.events` | `notification-service` | `notification-service` | All order events; Redis dedupe; dispatcher logs. |
| `order.events` | `shipping-delivery-service` | `shipping-service` | `order.paid` only; creates a shipment; PostgreSQL; idempotent on `(order_id, tenant_id)`. |
| `order.events` | `ai-orchestrator-service` | `ai-orchestrator` | `order.paid` only; purchase affinity; Redis dedupe. |
| `product.events` | `ai-orchestrator-service` | `semantic-indexer` | `product.created`, `product.updated`, `product.deactivated`; vector index; no Redis dedupe. |

Both topics use the `<topic>.dlq` convention. The diagram shows
`order.events.dlq` and `product.events.dlq`; the consumer retries three times at
two-second intervals before dead-lettering.

---

## 3. What the diagram deliberately does not draw

- **Data-store edges.** PostgreSQL, Cosmos DB, and Redis are in the service
  captions. Drawing one edge per store would turn the call graph into a data map.
- **The full saga compensation graph.** The diagram shows the synchronous calls
  and their operation labels; compensation behaviour belongs in
  [`REQUEST-FLOW-END-TO-END.md`](REQUEST-FLOW-END-TO-END.md).
- **Observability.** Metrics, traces, logs, and alerting are in the separate
  observability deep dive.
- **Mesh control plane.** `istiod` supplies xDS and workload certificates but is
  not on the per-request path. The sync lane note says so.

---

## 4. Known gaps visible in the picture

These are intentionally called out because the diagram is meant to be accurate,
not aspirational:

- The Debezium connector manifest exists, but the Kafka Connect cluster it needs
  is not in this repository.
- The payment provider is only a real HTTP call when the HTTP gateway is enabled;
  the local stub takes no money.
- The carrier adapter is a stub; no real carrier API is called.
- `notification-service` has no external notification vendor.
- `payment-service.void` does not call the provider, so a provider authorisation
  is left to expire rather than being explicitly released.
