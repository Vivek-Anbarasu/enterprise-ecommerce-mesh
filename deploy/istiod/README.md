# istiod values fragments

Control-plane configuration that **cannot** be expressed as namespaced Kubernetes
objects, and so cannot be rendered by the application chart.

Same reasoning as [`deploy/bicep`](../bicep/README.md): the chart creates objects in
the `ecommerce` namespace; it cannot reconfigure the Istio control plane it runs
inside. Editing this file and deploying is an operator action, and getting the
order wrong fails quietly or not at all.

## `meshconfig-tracing.yaml`

Defines the `otel-collector` OpenTelemetry extension provider in istiod's `MeshConfig`.

### Why this is not in the chart

A `Telemetry` resource can only reference a tracing provider *by name*, and that
name has to resolve inside istiod's `MeshConfig.extensionProviders`. The two
resources live in different scopes and are owned by different Helm releases.

There is no partial-credit failure mode. Reference a provider that is not defined
and istioctl rejects the whole `Telemetry` resource — so enabling
`istio.tracingProvider` before applying this file gives you mesh tracing that is
off, with no error explaining why.

The destination is the in-cluster OpenTelemetry Collector
(`deploy/observability/otel-collector/`), not Application Insights directly.
Azure Monitor's OTLP ingest path is authenticated and Istio's extension provider
has no header mechanism; the collector terminates OTLP and holds the connection
string.

### Deploy

Apply **first**, then enable the application side:

```bash
helm upgrade istiod istio/istiod -n istio-system \
  -f deploy/istiod/meshconfig-tracing.yaml
```

```bash
helm upgrade --install ecommerce deploy/helm/enterprise-ecommerce \
  -n ecommerce --create-namespace \
  -f deploy/helm/values-prod.yaml
```

Verify the provider is actually registered before concluding tracing works:

```bash
kubectl -n istio-system get cm istio -o jsonpath='{.data.mesh}' | grep -A5 extensionProviders
kubectl -n ecommerce get telemetry mesh-access-logging -o yaml | grep -A3 tracing
```

### What this does and does not give you

| | |
|---|---|
| **Waypoint (L7)** | Spans. Retries, timeouts, per-request balancing and the HBONE hop become visible with timings the application cannot measure. |
| **ztunnel (L4)** | **No spans, ever.** ztunnel does not parse HTTP and does not generate spans. Its activity is visible in access logs and TCP telemetry, not in Application Insights. This is the defining difference from sidecar mode and it is not fixable by configuration. |
| **`api-gateway`, `bff-service`** | **No spans.** Neither has a waypoint — they are entered from outside the mesh — so the mesh contributes nothing to their traces. Those two come entirely from Micrometer. |
| **`notification-service`** | **No spans.** Not enrolled, pure Kafka consumer. |

So "mesh tracing enabled" means *the waypoint contributes spans to traces that
already traverse it*, not *every packet is traced*.

### Sampling is a second, independent decision

The proxy samples on its own, using `global.tracingSampleRate` so the two halves
are nominally aligned. They are still two decisions made by two independent
samplers, so a small number of traces will contain a waypoint span whose
application parent was not sampled, and those appear in Application Insights as short traces
rooted at the proxy. That is expected noise, not a bug — but it means a
non-empty result for a trace id does not guarantee the application half is
present, and vice versa.

### Port note

This provider is configured for gRPC OTLP on **4317**. The applications export
over HTTP on **4318**. The collector terminates both, but the ports are not
interchangeable in this configuration — pointing the provider at 4318 will not
work.
