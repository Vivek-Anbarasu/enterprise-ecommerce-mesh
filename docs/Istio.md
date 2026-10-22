# Enterprise E-Commerce Service Mesh: Ambient Data Plane Governance

This document is the platform policy for the service mesh in the
`enterprise-ecommerce` deployment space on Azure AKS. It records, per service,
what the mesh does and does not do for it — and why.

It previously governed **sidecar injection**. That model is gone: see
[istio-ambient-industry-standard.md](istio-ambient-industry-standard.md) for why,
and [istio-ambient-architecture-decisions.md](istio-ambient-architecture-decisions.md)
for how the tiers fit together. The decision matrix below is the ambient
equivalent, and it is a different shape, because ambient moves the choice from
*injection* to *enrollment*.

---

## 1. The governance model changed, not just the mechanism

Under sidecar mode the question for every service was binary: **does this pod
get an Envoy sidecar, yes or no?** Injecting was opt-in per namespace, which
produced a permanent asymmetry — the namespace had to be labelled `enabled` for
the checkout path to be secured at all, which meant the Kafka workers got
sidecars too, and the governance document spent three paragraphs justifying
why they should not have.

Under ambient mode there is no such trade-off, and the asymmetry disappears:

| | Sidecar mode | Ambient mode |
|---|---|---|
| L4 (mTLS, identity, L4 authz, L4 telemetry) | per pod, opt-in, ~40MB each | per node, **always on**, no pod cost |
| L7 (routing, retries, outlier ejection, path authz) | same sidecar, bundled | shared waypoint, **opt-in per service** |

The only real decision left is **which services need Layer 7**. Everything else
is decided once, cluster-wide, by the namespace label, and costs nothing per
replica.

The practical consequences:

* A service is never "outside the mesh". Every pod is covered by ztunnel.
* A service without a waypoint is not *unprotected*, it is *L4-protected*. It
  still gets mTLS, identity and L4 authorization.
* Upgrading the mesh no longer restarts an application pod. This was the
  operational argument in the old document, and it is now simply true.

---

## 2. Ambient enrollment matrix

Enrollment is a property of the **destination**, recorded as
`istio.io/use-waypoint` on the Service. Callers are unaffected and are not
aware of it: ztunnel rewrites the outbound connection and the waypoint does the
Layer 7 work in the middle.

| Service | HTTP surface | Waypoint (L7) | Rationale |
| :--- | :--- | :---: | :--- |
| **Edge** `api-gateway` | `8080` `/api/*` | **NO** | Its traffic arrives from the ingress controller, and Istio models ingress-to-service separately from east-west traffic, so it does not traverse the destination waypoint anyway. Edge filtering — tenant resolution, `RedisRateLimiter`, Resilience4j — belongs in Spring Cloud Gateway, where the business context lives. |
| **Edge** `product-catalog-service` | `8085` `/api/*` | **YES** | Read-heavy and fanned out by the gateway. Needs per-request load balancing so one hot pod is not pinned by a long-lived client connection. |
| **Edge** `review-rating-service` | `8088` `/api/*` | **YES** | Same read-heavy pattern as the catalogue. |
| **Edge** `shopping-cart-service` | `8086` `/api/*` | **YES** | Per-shopper state keyed in Redis; request-level balancing keeps one replica from becoming a hotspot. |
| **Edge** `ai-orchestrator-service` | `8090` `/api/*` | **YES** | Calls are long-running and slow. Without a waypoint the caller's connection pool pins to a single slow pod; with one, a stalling backend is ejected rather than pinned. |
| **Core** `customer-service` | `8087` `/api/*` | **YES** | Called on nearly every request and from several places; needs request-level balancing and retries. |
| **Core** `order-service` | `8082` `/api/*` | **YES** | The checkout critical path. Carries the resilience budget and the canary split. |
| **Core** `payment-service` | `8084` `/api/*` | **YES** | Charged here. The waypoint gives retries and outlier ejection in front of it, so a stalling pod is pulled from the pool instead of timing out every shopper who reaches it. |
| **Core** `inventory-service` | `8083` `/api/*` | **YES** | Called by `order-service` deep inside the mesh, where a spring gateway filter cannot help. This is exactly the case the old architecture could not cover. |
| **Async** `shipping-delivery-service` | `8089` `/internal/shipping/*` | **YES** | **This corrects the previous document.** It sits on the async tier and mostly consumes Kafka, but it *does* expose an `/internal/shipping` REST surface for carrier webhooks. Path-level authorization of that surface is an L7 rule, so it needs a waypoint. Leaving it on L4 would mean either losing the rule or having ztunnel reject it outright. |
| **Async** `notification-service` | none (actuator only) | **NO** | A pure Kafka consumer. No HTTP business surface at all, so L7 has nothing to parse and a waypoint would be pure overhead. It still gets full ztunnel L4 treatment: mTLS, identity, L4 telemetry. |

### Why enrollment is per service and not per namespace

`istio.io/use-waypoint` on the **namespace** would force every Service in it
through Layer 7, including the Kafka-only workers. That is one line instead of
eleven, and it is the wrong trade: L7 compute is shared but not free, and
enrolling a destination that has no HTTP surface buys nothing.

The namespace label `istio.io/dataplane-mode: ambient` is the opposite case —
that one *is* namespace-wide, because there is no cost to L4 coverage and no
reason to leave a pod unencrypted.

---

## 3. Enforcement points, and what fails when each is wrong

Ambient enforces policy in two places, and mixing them up fails loudly in one
direction and silently in the other.

**ztunnel (L4)** enforces policies that *target* a workload with a `selector`.
It understands only L4 attributes: `principals`, `namespaces`, `ipBlocks`,
`ports`, and the `source`/`destination` conditions.

An L7 attribute — `paths`, `methods`, `headers` — is **not ignored**. ztunnel
fails safe and converts the policy into a blanket `DENY`. A selector-targeted
policy carrying `paths: ["/api/*"]` therefore blocks *all* traffic to that
workload, not merely the paths you named. That is why every path and method rule
in this repository is attached with `targetRefs` instead.

**the waypoint (L7)** enforces policies *attached* to a Service or to the
waypoint `Gateway`. This is where the path rules live.

One consequence catches people out: **a waypoint does not impersonate the source
workload.** Once a waypoint is in the path, the destination's ztunnel sees the
*waypoint's* identity, not the caller's. So for an enrolled service the correct
L4 rule is not "allow the workload SA" but "allow **only** the waypoint".

That is also the control that stops a waypoint outage silently downgrading the
mesh. `istio.io/use-waypoint` only records intent; ztunnel routes straight to
the destination if the waypoint is missing or has no address. The
`<service>-require-waypoint` policies close that gap by denying anything that
did not come through the waypoint:

```yaml
spec:
  selector:
    matchLabels:
      app.kubernetes.io/name: order-service
  action: ALLOW
  rules:
    - from:
        - source:
            principals:
              - "cluster.local/ns/ecommerce/sa/waypoint"
```

---

## 4. What ambient did *not* fix

The old document claimed three savings from removing sidecars. Here is what
actually happened to each:

* **~40MB of RAM per replica.** Realised, and then some. There is no proxy
  container at all now, so the saving applies to every service rather than to
  the handful the old chart happened to exclude.
* **Kafka TCP passthrough without deserialization.** Realised for a different
  reason than expected: there is no Envoy to deserialize. ztunnel is L4
  pass-through, so a Kafka connection costs it nothing to carry.
* **The istiod startup race.** Realised, and eliminated entirely rather than
  avoided. The application container no longer waits on a proxy to be ready,
  because there is no proxy in the pod.

It also introduced three things the old document never had to think about:

1. **NetworkPolicy must allow port 15008.** Ambient terminates HBONE on 15008
   and only then forwards to the application port, and NetworkPolicy is
   evaluated on the host, *before* the tunnel is unwrapped. A policy listing
   only 8080 silently breaks all east-west traffic.
2. **NetworkPolicy must allow the health-probe link-local addresses.** Kubelet
   probes are plaintext from the node and have no identity. Ambient SNATs them
   to `169.254.7.127/32` (and `fd16:.../128` for IPv6) and exempts those
   addresses from *mesh* policy — but a namespace default-deny still blocks
   them, so readiness fails on every pod in the namespace.
3. **`VirtualService` must go.** With the ambient data plane it is Alpha, and
   Istio does not support mixing it with Gateway API configuration. Routing,
   retries and timeouts are now `HTTPRoute`; load balancing and outlier
   ejection come from the waypoint's defaults rather than a `DestinationRule`.

---

## 5. The edge is the one place the mesh cannot authenticate

`ingress-nginx` reached end of life in March 2026. The replacement is **Azure
Application Gateway for Containers**, whose data plane runs **outside** the
cluster. Unlike ingress-nginx, there is no pod to label, no namespace to select
on and no ServiceAccount to authorize by.

That has three consequences, and they are the reason the edge is treated
separately everywhere in this repository:

| | ingress-nginx (retired) | Application Gateway for Containers |
|---|---|---|
| Where it runs | in-cluster, as a Deployment | **outside** the cluster |
| Mesh identity | could be given one by labelling the namespace `ambient` | **none, and none can be granted** |
| Reaches the gateway over | HBONE / mTLS | plaintext inside the VNet |
| Authorization | `principals` | `ipBlocks` (address, not identity) |

So:

* **Transport.** `api-gateway`'s serving port is the single `PERMISSIVE`
  exception in an otherwise `STRICT` namespace, via a workload-scoped
  `PeerAuthentication` with `portLevelMtls`. Everything east of the gateway —
  the checkout path above all — stays `STRICT`.
* **Authorization.** `AuthorizationPolicy/gateway-edge-only` matches on
  `ipBlocks` rather than `principals`, and is mirrored exactly by a
  `NetworkPolicy` of the same name. They must agree; a policy that allows one
  and not the other is a policy that does not work.

AGC's built-in Istio integration would have handled all of this automatically —
but it assumes sidecars, **injects one**, and explicitly does not support
ambient mode. It is unusable here.

**This is the weakest point in the design, and it is deliberate.** The fully
authenticated alternative is an Istio ingress gateway (`gatewayClassName: istio`)
between AGC and the application, which gives AGC a real mesh peer to authenticate
against, at the cost of one more L7 hop. See
[AKS-DEPLOYMENT.md](AKS-DEPLOYMENT.md) §5.4.

---

## 6. What this document does not cover

* **Egress containment.** ztunnel enforces authorization on the *receiving*
  side of a connection, so a namespace-wide ALLOW policy listing destination
  hosts is an ingress policy that happens to look like an egress policy and
  provides no egress containment at all. Real egress control here is
  `NetworkPolicy`. Hostname-level egress policy would need a waypoint deployed
  as an egress gateway, which this release does not do. The previous version
  of this mesh configuration carried an egress allow-list that had no effect;
  it has been removed rather than kept as decoration.
* **Trust of the edge hop.** The AGC → `api-gateway` hop is plaintext inside the
  VNet, protected by source address, the WAF and TLS termination at the edge.
  Everything *inside* the cluster is mTLS and identity-checked. See §5 and
  [AKS-DEPLOYMENT.md](AKS-DEPLOYMENT.md) §5.4.