# Microservice Resilience: API Gateway vs. Service Mesh Waypoint

This document details the architectural separation of concerns between code-level frameworks (**Resilience4j**) and platform-level infrastructure (**Istio Ambient Waypoint Proxy**).

---

## 🏛️ Direct Architectural Comparison

| **Feature** | **Istio Waypoint Proxy (Envoy)** | **Resilience4j (Application Library)** |
| :--- | :--- | :--- |
| **Where it Runs** | Out-of-process, as an isolated infrastructure deployment. | In-process, compiled directly inside your Java/Spring JAR. |
| **Language Dependency** | **Polyglot.** Works for Go, Node.js, Python, or Rust with no changes. | **Java Only.** Restricted to the JVM ecosystem. |
| **Retries & Timeouts** | **Excellent.** Handles generic network blips, TCP glitches, and basic HTTP error statuses. | **Excellent.** Can retry selectively based on specific business exception rules. |
| **Circuit Breaking** | **Black-box.** Drops connections if a pod replica starts throwing HTTP `5xx` statuses. | **White-box.** Tracks internal errors, database deadlocks, or slow API methods. |
| **Fallback Mechanics** | **Static/Network Routing Only.** Redirects traffic to a backup domain or status page. | **Dynamic.** Runs local recovery methods, fetches stale cache, or computes logic alternatives. |

---

## 🛡️ The "Defense-in-Depth" Pattern (Industry Standard)

To achieve true zero-trust and high availability, modern architectures split responsibilities to prevent configuration redundancy and keep codebases maintainable.

### 1. Offload "Dumb" Network Tasks to the Waypoint
Do not pollute your application code with boilerplate configurations for standard network instabilities. Let the **Waypoint Proxy** handle:
* **Generic Retries & Timeouts:** Automatically catching clean network drops on port calls.
* **Cross-Service Rate Limiting:** Enforcing global rate constraints between communication namespaces.
* **Canary & Blue-Green Traffic Routing:** Directing traffic distributions across separate target deployments.

### 2. Keep "Smart" Context-Aware Tasks in Resilience4j
Use **Resilience4j** exclusively for edge cases where the application must react intelligently to structural backend business logic failures. Let it handle:
* **Dynamic Business Fallbacks:** For instance, if the `payment-service` fails, the local code intercepts the failure and dynamically triggers a fallback to queue the order for an asynchronous retry later.
* **In-App Bulkheads:** Restricting thread pool saturation inside the application container to stop a single lagging thread pool from taking down the entire service.
* **Database/State Failure Circuit Breakers:** Opening a local code circuit when stateful external transactions (like a PostgreSQL deadlock) degrade, avoiding unnecessary network hops to a database that is already down.

---

## 🚨 What Breaks If You Remove the Waypoint Proxy?

If you completely remove the `waypoint` proxy and try to rely solely on **Resilience4j on the API Gateway**, the gateway becomes structurally blind to what happens deep inside your cluster namespaces. 

* **Silent TCP Retries Fail:** Resilience4j operates purely at Layer 7. If a backend pod drops a connection at the TCP socket layer or suffers a network timeout during a container rollout, Resilience4j must trigger a heavy application thread loop, re-running the entire Spring interceptor stack. This adds latency and memory overhead. A `waypoint` (or `ztunnel`) performs silent retries instantly at the kernel socket layer inside microseconds, without the application ever knowing a connection blipped.
* **No Network-Level Circuit Breaking between Backend Pods:** The API Gateway only talks to a single, unified Kubernetes Service abstraction IP (e.g., `http://order-service`). It relies on standard `kube-proxy` to distribute requests. If `order-service-pod-A` breaks but `pod-B` and `pod-C` are healthy, Resilience4j will trip its circuit breaker for the **entire service** if the failure threshold is crossed. It will completely block traffic to the healthy pods. The `waypoint` proxy tracks the health of **individual pod IP replicas** (Outlier Ejection), temporarily removing only the broken pod from the pool while keeping the healthy pods active.
* **No Canary Traffic Splitting:** Resilience4j is a fault-tolerance framework, not a traffic router. It has no mechanism to intelligently shift packet distribution matrices. If you deploy a new version of a microservice (`v2`), Resilience4j cannot smoothly distribute traffic 90/10 based on HTTP header criteria. You would have to write complex custom Java code inside Spring Cloud Gateway using custom routing predicates, hardcoding target replica weights. The `waypoint` proxy handles this natively outside application code using standard Kubernetes `HTTPRoute` weights.

---

## 🎯 Summary Checklist
Without a Layer 7 proxy layer (`waypoint`) in your ambient service mesh:
* ❌ **Pod Isolation:** You lose the ability to isolate and eject a single failing pod replica dynamically.
* ❌ **Zero-Touch Config:** Application developers must manually write, configure, and maintain `application.yml` retry properties for every downstream route change.
* ❌ **Polyglot Uniformity:** If you introduce a Go, Node.js, or Python microservice later, Resilience4j cannot help them; you would have to source separate resilience libraries for every single language runtime.


Core Uses of Resilience4j at the Edge

• Cluster Saturation Prevention (Bulkhead Pattern): Restricts the maximum number of concurrent execution threads an external client can consume. If a downstream microservice experiences high latency, Resilience4j isolates those execution paths at the gateway, preventing a single slow backend from locking up all gateway worker threads and crashing your entire entry point.

• Intelligent User Fallbacks: Executes alternative programmatic logic if a downstream dependency drops out completely. Instead of returning a raw network error (503 Service Unavailable), the edge gateway catches the circuit breaker failure and delivers a safe fallback payload—such as pulling a stale snapshot from a local Redis cache or returning a friendly empty array.

• Edge-Facing Rate Limiting: Enforces traffic smoothing constraints right at the ingress perimeter. It protects your downstream Kubernetes cluster infrastructure from unexpected load spikes, brute-force security discovery sweeps, and targeted denial-of-service (DoS) attempts before traffic consumes internal network bandwidth.

• Opaque External Client Isolation: Wraps brittle network touchpoints that fall outside your service mesh control boundaries. If your API gateway needs to query an external, third-party authentication API (like Auth0, Okta, or a payment clearhouse), Resilience4j manages those connections with local timeouts and circuit breakers, isolating external third-party outages completely.


---

## Route-Level Bulkheads: Bounding Concurrency, Not Just Failures

Rate limiting and circuit breaking are the two controls every Resilience4j deployment starts with. On a reactive gateway they leave one gap open.

**Rate limiting bounds arrivals.** `replenishRate` and `burstCapacity` cap how many requests a tenant may *send* per second.

**Circuit breaking bounds retries.** It bounds how long the gateway keeps talking to a dependency that is *failing*.

Neither bounds how many requests may be **in flight at the same time** against one slow dependency. Those are the requests that matter, because each holds a pooled Reactor Netty connection for its entire duration, and in this gateway every route shares one connection pool. A dependency that is slow but perfectly healthy trips no circuit at all; it just quietly consumes the pool until a shopper trying to reach checkout is queued out by a burst of product recommendations.

That is the AI shape. `aiCircuitBreaker` allows a call to run for thirty seconds, so a modest burst of slow inference calls pins a proportional slice of the shared pool for half a minute each.

### What the Bulkhead Adds

| | Rate Limiter | Circuit Breaker | Bulkhead |
| :--- | :--- | :--- | :--- |
| **Bounds** | arrivals per second | retries against a failing dependency | concurrent in-flight calls |
| **Reacts to** | volume | failure rate | sustained slowness |
| **Fails by** | rejecting early | rejecting after N failures | rejecting when full |

Three properties are why this is a hand-written `BulkheadGatewayFilterFactory` rather than a configuration flag. Spring Cloud Gateway's built-in `CircuitBreaker` filter builds a `ReactiveResilience4JCircuitBreaker`, which composes a circuit breaker and a time limiter and has no bulkhead stage at all; there is no property that switches one on.

**1. It rejects, it does not queue.** `maxWaitDuration: 0` is not a tuning preference, it is a correctness requirement. A queued request is still an open client socket and still an open pooled connection, so queueing recreates the congestion the bulkhead exists to shed. Resilience4j's `tryAcquirePermission()` blocks the *calling thread* for up to `maxWaitDuration`, and in a reactive gateway that thread is a Netty event loop - so a non-zero wait parks an event loop in exactly the way the bulkhead was added to prevent. The filter refuses to start if it is configured non-zero.

**2. Permits are released on cancellation, not just completion.** A shopper closing the tab mid-inference cancels the chain rather than completing it. The filter uses `doFinally`, never `doOnSuccess` or `doOnTerminate`. A leaked permit is invisible - no exception, no failed request, no metric - and a bulkhead can only ever shrink as permits leak, so the route would be dead to new traffic minutes after launch with nothing in the logs to explain it.

**3. It sits inside the circuit breaker.** Listed last in the route's filter list, so an already-open circuit sheds without spending a permit, and only calls genuinely still going downstream are capped. The two controls are complementary: the breaker bounds how long the gateway keeps trying a broken dependency, the bulkhead bounds how many callers may be trying it at once.

### Sizing

Worst-case route throughput is `maxConcurrentCalls / breakerTimeout`, so the two settings must be read together.

| Route | Permits | Breaker timeout | Worst case | Rationale |
| :--- | :--- | :--- | :--- | :--- |
| `ai-orchestrator` | 10 | 30s | ~20 req/min | Recommendations are not what anyone came to buy, and the pool they contend for is the one catalogue and checkout need. |
| `payment` | 25 | 8s | ~3/sec when degraded | Caps what a card-network latency event can spend. At a healthy ~500ms it comfortably clears the 20/s payment rate limit. |

Deliberately **not** applied to `product-catalog`, `shopping-cart` or `inventory`. Those are the routes that must stay available during an AI incident, and capping them would only give a second thing to fail.

Both are overridable per deployment via `BULKHEAD_AI_MAX` and `BULKHEAD_PAYMENT_MAX`.

### Two Silent Failure Modes

Both produce a gateway that starts cleanly and provides no isolation whatsoever, which is why `GatewayBulkheadConfigurationTest` boots the real context and asserts them:

- **A bulkhead name with no matching `resilience4j.bulkhead.instances` entry.** `registry.bulkhead(name)` creates an instance from the registry default of 25 concurrent calls rather than failing, so a typo yields a route that runs wide open with nothing in the logs. The filter uses `registry.find(name)` and refuses to start instead.
- **A missing `resilience4j-bulkhead` dependency.** It is *not* a transitive dependency of `spring-cloud-starter-circuitbreaker-resilience4j`, which ships only circuitbreaker and timelimiter. Without it there is no `Bulkhead` class, so `BulkheadAutoConfiguration` backs off and there is no `BulkheadRegistry` to bind `resilience4j.bulkhead.instances.*` against. `resilience4j-reactor` is also required and also not transitive - without it the reactive Resilience4j auto-configuration backs off and the `CircuitBreaker` filter factory does not exist at all, so no route that names that filter can be built.

### Observing It

Rejection publishes to the standard `resilience4j.bulkhead` meters (`calls` tagged `kind=permitted|rejected`, `available_concurrent_calls`). `BulkheadGatewayFilterFactory` additionally logs the *first* rejection on a route at WARN and the rest at DEBUG, because shedding is the normal outcome under sustained overload and logging every one of them would turn a load spike into a log-flooding incident. Shed requests are forwarded to the same RFC 9457 fallback the circuit breaker uses, with the cause recorded so `FallbackController` can report "at capacity" rather than the misleading "circuit breaker open".