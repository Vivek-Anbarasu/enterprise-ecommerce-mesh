# Architectural Decision Record: E-Commerce Microservices Platform
## Modernization with Istio Ambient Mode, Spring Cloud Gateway, & OpenTelemetry

---

## 1. The Global Architecture Blueprint

This document defines the production topology for the enterprise E-commerce microservices cluster deployed on **Azure Kubernetes Service (AKS)**. It establishes the operational boundaries, protocols, and data pathways across the entire stack—from the public cloud edge down to internal database transactions.

### Component Topology Matrix

| Tier | Component | Layer | Scope | Runtime Location | Primary Network Protocol |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Edge WAF** | **Azure App Gateway** | Layer 7 | Cluster Boundary | Azure Cloud Managed | HTTPS (External TLS) |
| **Cluster Entry** | **Azure Application Gateway for Containers** | Layer 7 | North-South Entry | *Out-of-cluster data plane* | HTTPS to AGC · HTTP/1.1 into the gateway (see §5) |
| **Application Router** | **Spring Cloud Gateway** | Layer 7 | App Logic Edge | Microservice Pod | HTTP/1.1 Plaintext (Internal) |
| **Zero-Trust Network** | **Istio Ztunnel** | Layer 4 | Node Security | Host DaemonSet (Rust) | HBONE (HTTP/2 CONNECT via mTLS) |
| **Internal Engine** | **Istio Waypoint Proxy** | Layer 7 | Namespace Traffic | Out-of-pod Envoy Deploy | HTTP/1.1 Stream Decoupling |
| **Core Engine** | **Spring Boot Microservices** | Layer 7 | Business Logic | Target Workload Pods | HTTP/1.1 Plaintext |
| **Cluster Discovery** | **kube-proxy** | Layer 4 | Service Routing | Host Kernel (iptables) | TCP Packet Redirection |
| **Observability Pipeline** | **Micrometer + OTel + Collector** | App Spans | Telemetry | In-JVM Client to Collector | OTLP over HTTP/gRPC |

---

## 2. Structural Interaction Diagrams

### Diagram 1: Architecture Comparison (Sidecar vs Ambient Mode)

#### Legacy Sidecar Architecture (Pod-Level Bloat)
```
+-------------------------------------------------------------------------+
| Pod Boundary (E.g., Order Namespace Pod Replica)                         |
|                                                                         |
|  +---------------------------+           +---------------------------+  |
|  |  Spring Boot Application   |  HTTP/1.1 |    Envoy Sidecar Proxy    |  |
|  |  (JVM Memory Footprint)    |---------->|    (istio-proxy Container)|  |
|  +---------------------------+           +---------------------------+  |
+-------------------------------------------------------------------------+
       |                                                 |
       | (Bypasses Local Stack)                          | (Handles Crypto/mTLS)
       v                                                 v
  Plain Text Network Wire                          Encrypted mTLS Link
```

#### Modern Ambient Architecture (Decoupled Infrastructure)
```
+-------------------------------------------------------------------------+
| Pod Boundary (E.g., Order Namespace Pod Replica)                         |
|                                                                         |
|  +-------------------------------------------------------------------+  |
|  |                    Spring Boot Application                        |  |
|  |        (Clean Lifecycle - No Injected Proxy Container)            |  |
|  +-------------------------------------------------------------------+  |
+-------------------------------------------------------------------------+
                                 |
                                 v (eBPF Interception at Node Card)
+-------------------------------------------------------------------------+
| Host Node Boundary                                                      |
|                                                                         |
|  +-------------------------------------------------------------------+  |
|  |         Shared Node Daemon (Istio Ztunnel - Written in Rust)      |  |
|  +-------------------------------------------------------------------+  |
+-------------------------------------------------------------------------+
                                 |
                                 v (HBONE Enveloped Pipe)
                       Secure Mesh Network Wire
```

---

### Diagram 2: The End-to-End Dynamic Traffic Path

The diagram below tracks the lifetime of a transactional client request passing through the infrastructure tiers:

```
[ Public Client Request ]
           │
           ▼ (HTTPS / Public TLS)
┌──────────────────────────────────────┐
│ Azure App Gateway for Containers    │  <-- WAF + TLS offload. Data plane runs
│ (WAF · ALB Controller in cluster)    │      OUTSIDE the cluster: no mesh identity,
└──────────────────┬───────────────────┘      so the next hop is authorized by address
                   │
                   ▼ (HTTP/1.1 Plaintext inside the VNet. Port 8080 is
                   ▼  PERMISSIVE — the sole exception in a STRICT namespace.)
┌──────────────────────────────────────┐
│       Spring Cloud Gateway           │  <-- Triggers Redis Rate Limiting & Resilience4j
└──────────────────┬───────────────────┘
                   │
                   ▼ (Calls http://order-service DNS via kube-proxy Layer 4 lookup)
┌──────────────────────────────────────┐
│       Local Node Ztunnel             │  <-- Intercepts outbound TCP, wraps in L4 mTLS
└──────────────────┬───────────────────┘
                   │
                   ▼ (Encrypted HBONE Tunnel - Hop 1)
┌──────────────────────────────────────┐
│  Waypoint Proxy (Namespace Envoy)    │  <-- Unwraps HTTP/1.1, balances persistent pools
└──────────────────┬───────────────────┘
                   │
                   ▼ (Encrypted HBONE Tunnel - Hop 2)
┌──────────────────────────────────────┐
│     Target Node Ztunnel (Ingress)    │  <-- Decrypts transport frame securely
└──────────────────┬───────────────────┘
                   │
                   ▼ (Delivers plain stream to target socket)
┌──────────────────────────────────────┐
│    Order Microservice (Spring Boot)  │  <-- Executes business logic & updates database
└──────────────────────────────────────┘
```

---

## 3. Deep-Dive Component Coexistence & Interactions

### 1. NGINX Ingress and Spring Cloud Gateway
* **How They Interact:** NGINX handles cluster-level ingress routing, parsing broad domain names and routing the traffic down to the `api-gateway` pod. Spring Cloud Gateway intercepts that entry flow and applies deep application filters—including multi-tenant `RedisRateLimiter` token evaluations and internal URL structural filtering.
* **Ambient Value-Add:** Ambient mode remains transparent at this layer. The Ingress and the Application Gateway continue using clean, unencrypted internal HTTP/1.1 communication, but the cluster infrastructure secures the underlying traffic paths seamlessly via `ztunnel` without requiring you to patch application routes.

### 2. kube-proxy and Istio Ztunnel
* **How They Interact:** When Spring Cloud Gateway calls a downstream service (e.g., `http://order-service`), `kube-proxy` rules evaluate the virtual cluster IP address to identify which target backend pod IPs are available. Instantly, the Rust-based `ztunnel` uses eBPF to intercept that traffic stream right at the host network card level.
* **Ambient Value-Add:** `kube-proxy` provides basic IP addressing, but `ztunnel` turns that plain text data stream into a secure, encrypted **HBONE tunnel** (HTTP/2 CONNECT encapsulation). This delivers **instant, zero-overhead mTLS** across the cluster without affecting your existing CoreDNS or standard Kubernetes routing rules.

### 3. Spring Cloud Gateway Filters vs. Istio Waypoint Proxies
* **How They Interact:** Spring Cloud Gateway handles external edge filtering (e.g., stripping cookies via `RemoveRequestHeader=Cookie`, executing multi-tenant rate limits, and processing edge circuit breaking via Resilience4j). Meanwhile, the **Waypoint Proxy** sits deeper in the internal mesh, managing traffic between downstream microservices (East-West traffic).
* **Ambient Value-Add:** Your application gateway is exceptional at managing traffic at the edge, but it cannot see or help if the `order-service` suffers a brownout while talking directly to the `inventory-service` deep within the cluster. Waypoint proxies handle that internal traffic, breaking persistent HTTP/1.1 connection pools to balance requests evenly and providing infrastructure-level retries and circuit breakers right where the microservices communicate.

### 4. Micrometer + OpenTelemetry + Collector vs. Istio Telemetry
* **How They Interact:** Your Spring Boot applications use Micrometer and OpenTelemetry to pass tracking codes (like `traceparent` headers) along with every REST request. Those spans go to an in-cluster OpenTelemetry Collector, which exports them to Application Insights and is the only workload holding the ingestion credential.
* **Ambient Value-Add:** Application tracing shows you what happens *inside* your Java code, but it is blind to network-level issues. Istio's proxies automatically attach infrastructure-level data to your existing traces. This allows you to see exactly how much time a request spent waiting inside network queues, crossing physical wires, or negotiating mTLS handshakes. Furthermore, if a Spring Boot container suffers a sudden system crash, Istio's out-of-process proxies will still report a clear network failure span to the collector, ensuring you don't lose visibility during outages.

---

## 4. Architectural Summary

By implementing this modern architecture, your microservices platform achieves complete **separation of concerns**:

1. **Application Logic (Spring Boot + Gateway):** Manages business workflows, API routes, multi-tenant rate limiting, and core code execution.
2. **Application Visibility (OTel + Micrometer):** Provides deep insight into code execution paths and database transaction performance.
3. **Infrastructure Security (Ztunnel):** Enforces high-performance, cluster-wide mTLS encryption at Layer 4 with minimal resource usage.
4. **Infrastructure Resilience (Waypoint):** Solves the connection-sticking issues common to persistent microservice pools, delivering true Layer 7 request load balancing, automatic retries, and network isolation out-of-process.