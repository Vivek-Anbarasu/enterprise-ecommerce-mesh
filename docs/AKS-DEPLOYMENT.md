# Deploying to Azure Kubernetes Service

Covers provisioning a cluster, wiring managed data services, and shipping the
mesh to production.

## Prerequisites

- Azure CLI 2.60+ and `az aks` extension
- `kubectl` v1.34+ (matches the tested AKS version)
- `helm` 3.15+ and `istioctl` 1.31+
- An Azure Container Registry for images
- Workload identity enabled on the cluster (see below)

## 1. Provision the cluster

```bash
az group create --name rg-ecommerce-prod --location westeurope

az aks create \
  --resource-group rg-ecommerce-prod \
  --name aks-ecommerce-prod \
  --node-count 3 \
  --node-vm-size Standard_D4s_v5 \
  --enable-managed-identity \
  --enable-workload-identity \
  --enable-oidc-issuer \
  --network-plugin azure \
  --network-policy azure \
  --enable-azure-policy \
  --enable-addons monitoring \
  --kubernetes-version 1.34
```

Three deliberate choices here:

- **`--network-policy azure`** makes the cluster honour the NetworkPolicies in the
  chart. Without it the default-deny policies render but do nothing.
- **`--enable-workload-identity`** lets pods authenticate to Azure services with a
  federated identity instead of a stored client secret. This is the basis of
  secretless operation.
- **`--enable-azure-policy`** blocks pods that exceed the resource quota, rather
  than letting one misbehaving service consume the node pool.

Production should use system node pools plus a dedicated user pool, and enable
Pod Disruption Budgets and topology spread (both already in the chart).

### 1.1 The cluster needs a subnet for Application Gateway for Containers

AGC is installed into a **delegated subnet**, and it must be a `/24`. A `/23`
works until you later move the cluster to CNI Overlay, at which point AGC must
be recreated and the migration causes an outage.

```bash
az network vnet subnet create \
  -g rg-ecommerce-prod --vnet-name vnet-ecommerce \
  --name agc-subnet --address-prefixes 10.0.1.0/24 \
  --delegations Microsoft.Network/applicationGateways
```

Record that prefix: it becomes `apiGateway.backendCidrs`, which is what
authorizes AGC to reach `api-gateway` (see §5.4).

## 2. The north-south edge: Application Gateway for Containers

`ingress-nginx` reached end of life in **March 2026** and the AKS
application-routing add-on that ships it is supported only until **November
2026**. This platform uses **Application Gateway for Containers (AGC)** instead,
driven through **Gateway API** rather than the frozen Ingress API.

Two things make this more than a class-name change:

* AGC's data plane runs **outside** the cluster. There is no ingress pod. That
  breaks the previous `NetworkPolicy`/`AuthorizationPolicy` selectors, which
  both keyed on the `ingress-nginx` namespace, and it breaks mesh `STRICT` mTLS
  — see §5.4.
* AGC **cannot reference a Key Vault certificate**. Its certificates must be a
  Kubernetes Secret in the cluster, so the `kubernetes.azure.com/tls-cert-keyvault-uri`
  annotation has no equivalent. The chart syncs the cert from Key Vault with the
  Secrets Store CSI driver instead (§4).

### 2.1 Install the ALB Controller

Either the AKS add-on:

```bash
az feature register --namespace Microsoft.ContainerService --name ManagedGatewayAPIPreview
az feature register --namespace Microsoft.ContainerService --name ApplicationLoadBalancerPreview

az aks update -g rg-ecommerce-prod -n aks-ecommerce-prod \
  --enable-gateway-api --enable-application-load-balancer
```

…or the Helm chart, which is the supported route if you need to customise the
deployment (node selectors, tolerations). The add-on is currently in preview and
does not allow customising the identity or namespace:

```bash
az identity create -g rg-ecommerce-prod -n azure-alb-identity

helm install alb-controller oci://mcr.microsoft.com/application-lb/charts/alb-controller \
  --namespace alb-system --create-namespace \
  --version 1.11.4 \
  --set albController.namespace=azure-alb-system \
  --set albController.podIdentity.clientID=$(az identity show \
       -g rg-ecommerce-prod -n azure-alb-identity --query clientId -o tsv)
```

Verify. `Accepted: True` with message `Valid GatewayClass` is what you want:

```bash
kubectl get gatewayclass azure-alb-external -o yaml
kubectl get pods -n azure-alb-system
```

### 2.2 Create the Application Gateway for Containers

Pick one strategy and set the matching values.

**Managed by the ALB Controller** — the controller owns the Azure resources:

```bash
kubectl apply -f - <<EOF
apiVersion: alb.networking.azure.io/v1
kind: ApplicationLoadBalancer
metadata:
  name: ecommerce-agc
  namespace: ecommerce
spec:
  associations:
  - /subscriptions/.../resourceGroups/rg-ecommerce-prod/providers/Microsoft.Network/virtualNetworks/vnet-ecommerce/subnets/agc-subnet
EOF
```

**BYO** — you created the resource with ARM/Bicep/Terraform, and the Gateway
references it by ID instead. Best where the Azure resource lifecycle has to stay
under change control.

### 2.3 Verify the edge before touching DNS

Do not point DNS at the new frontend until this passes:

```bash
kubectl -n ecommerce get gateway public-edge -o jsonpath='{.status.conditions}'
# Accepted: True, Programmed: True, and an address assigned

kubectl -n ecommerce get secret api-gateway-tls   # must exist and be kubernetes.io/tls
```

Then hit the frontend FQDN directly with a host override, bypassing DNS:

```bash
FQDN=$(kubectl -n ecommerce get gateway public-edge -o jsonpath='{.status.addresses[0].value}')
curl -k --resolve api.enterprise-ecommerce.com:443:$FQDN \
  https://api.enterprise-ecommerce.com/actuator/health
```

Only then update the DNS A/CNAME record. Allow time for the TTL to drain, and
move it during a low-traffic window.

### 2.4 Two operational constraints worth knowing up front

* **Listener ports are 80 and 443 only.** AGC will not bind any other port.
* **Connection draining is fixed at 5 minutes** and cannot be configured. On
  scale-in, connections are held open for 5 minutes before being closed. Pods
  removed from the backend keep their existing connections until the client
  disconnects.



### 2.5 Per-IP rate limiting at the edge

Worth setting up early, and the reason is not where you would expect. The
`api-gateway` **cannot** rate limit anonymous traffic by IP any more: `bff-service`
sits in front of the browser traffic, so every anonymous storefront request arrives
at the gateway from a BFF pod address. Per-IP bucketing at the gateway would pool
every shopper in the world into a handful of buckets. It would stop protecting
anything while looking correctly configured.

AGC terminates ahead of the BFF and still holds the real client address, so it is
the only layer that can do this job.

WAF on Application Gateway for Containers is **two-sided**, and both halves are
required:

| Half | Where | What it does |
|---|---|---|
| WAF policy + `SecurityPolicy` child resource | Azure (`deploy/bicep/waf-policy.bicep`) | Defines the rules, and makes the policy referenceable by the ALB Controller |
| `WebApplicationFirewallPolicy` custom resource | Cluster (this chart) | Says which policy applies, and at what scope |

Half of either is a **silent no-op**: AGC shows the policy as associated in the
portal and in Defender for Cloud, inspects no traffic, and emits no firewall logs.

```bash
# 1. Azure half. Start in Detection.
az deployment group create \
  --resource-group rg-ecommerce-prod \
  --template-file deploy/bicep/waf-policy.bicep \
  --parameters agcResourceId=<agc-arm-id> policyMode=Detection

# The policy ID is an output. Feed it to the chart:
#   --set webApplicationFirewall.policyResourceId=<id>
#   --set webApplicationFirewall.enabled=true

# 2. Verify. An unattached policy reports itself rather than failing.
kubectl -n ecommerce get webapplicationfirewallpolicy ecommerce-waf -o yaml
```

Then confirm traffic is actually being inspected. **No WAF log entries means no
enforcement.** Check `policyScopeName` and `policyScope` in the log fields, and
re-check that `targetRef` names the `public-edge` Gateway.

#### Sizing: the threshold is per instance

Each AGC instance keeps its own rate-limit counter, so the configured threshold is
**not** a gateway-wide limit. With a threshold of 400/min and two instances seeing
270 and 230 requests from one client, neither trips and all 500 pass. Treat the
number as "per instance", and divide your real per-client budget by the instance
count when choosing it.

#### Two constraints specific to AGC

- **Only DRS 2.1.** No CRS managed rule sets. Confirm your AGC WAF engine actually
  supports rate limit rules before relying on them: the rate-limit documentation
  calls for the latest engine, and AGC pins DRS 2.1. This is the one assumption
  in this design worth checking against a real deployment first.
- **No custom block response.** A blocked request returns a bare `403`, so the
  storefront cannot show a friendly "slow down" page for a WAF rejection. Handle
  it as an opaque 403 in the SPA.
## 3. Managed data services

Self-hosted databases on nodes are a single failure domain. In production use:

| Purpose | Azure service | Notes |
|---|---|---|
| PostgreSQL | Azure Database for PostgreSQL Flexible Server | Zone-redundant HA, one database per service |
| Cache | Azure Cache for Redis Premium | Cluster mode, eviction policy `allkeys-lru` |
| Broker | Azure Service Bus (Premium) or Event Hubs | See the Kafka note below |
| Catalogue / reviews / AI | Azure Cosmos DB for MongoDB | Wire-compatible with the Mongo driver |
| Secrets | Azure Key Vault | Mounted via the CSI driver |

On AKS, prefer **Azure Event Hubs** (its Kafka-compatible endpoint) over running
Kafka in the cluster: it removes broker state from the cluster and gives you
partition/reserved-throughput guarantees. Service Bus has no Kafka endpoint; the
`*.servicebus.windows.net` host is shared, but only Event Hubs listens on
`:9093`. Set:

```yaml
infrastructure:
  kafka:
    bootstrapServers: <namespace>.servicebus.windows.net:9093
    securityProtocol: SASL_SSL
    saslMechanism: PLAIN
    jaasSecretName: kafka-sasl     # resolves to <release>-kafka-sasl
```

Every service that uses Kafka is configured for SASL_SSL through
`spring.kafka.properties.*`; the defaults stay `PLAINTEXT` so Docker Kafka
continues to work. Event Hubs accepts the namespace connection string as the SASL
password, so create the Secret once per namespace:

```bash
kubectl -n ecommerce create secret generic ecommerce-kafka-sasl \
  --from-literal=jaas-config='org.apache.kafka.common.security.plain.PlainLoginModule required username="$ConnectionString" password="<event-hubs-connection-string>";'
```

The chart injects it as `KAFKA_SASL_JAAS_CONFIG`; no credential is ever rendered
from `values.yaml`. Prefer `OAUTHBEARER` with workload identity once the Azure
Identity callback handler is on the classpath.

If you must run Kafka in-cluster, use a StatefulSet with a node-local Persistent
Volume per broker. Replication factor 3 across availability zones is required
before production traffic; the chart's default topic config already sets
`min.insync.replicas: 2`, which will refuse to produce if that is not met.

## 4. Key Vault and the CSI driver

Install the secrets-store CSI driver and enable secret rotation:

```bash
az aks enable-addons --resource-group rg-ecommerce-prod --name aks-ecommerce-prod \
  --addon secrets-store

# Application secrets are mounted by a SecretProviderClass named
# `keyvault-provider` (referenced by the chart). Its vault URI, tenant id and
# object names are environment-specific, so create it per cluster — it is not
# committed in this repository.
```

The chart's `keyVault.enabled: true` mounts those secrets at `/mnt/secrets`.
The JWT verification key in particular is read from
`/mnt/secrets/jwt/public-key.pem`, so rotating it is a Key Vault operation plus a
pod restart, not a redeploy of secrets through Helm.

### 4.1 The edge certificate

The edge TLS certificate needs the same driver but a different mechanism. AGC
cannot read a Key Vault reference, so the chart renders a `SecretProviderClass`
that syncs `tls.crt` and `tls.key` into a `kubernetes.io/tls` Secret named
`api-gateway-tls`, which the Gateway's `certificateRefs` then points at.

```yaml
apiGateway:
  certificate:
    secretName: api-gateway-tls
    syncFromKeyVault: true
```

Substitute `PLACEHOLDER_EDGE_CERT_NAME` in
`deploy/helm/enterprise-ecommerce/templates/tls-certificate.yaml` with the name of
your Key Vault certificate (usually the DNS name of the hostname), then:

```bash
kubectl -n ecommerce get secret api-gateway-tls
# TYPE must be kubernetes.io/tls. If the type is Opaque the Gateway listener
# silently never reaches Programmed.
```

Rotation stays a Key Vault operation and propagates on `refreshInterval` (1h).
This keeps the certificate out of git, out of environment variables and out of
etcd's non-encrypted state — the same property the rest of the secret handling
has.

## 5. Istio in ambient mode

The mesh is **ambient**: a shared per-node `ztunnel` provides Layer 4 to every
pod, and an optional shared `waypoint` provides Layer 7 to the services that
need it. There are no sidecars.

### 5.1 The CNI agent must be ambient-capable

`istio-cni` is what redirects pod traffic into the mesh. In ambient mode it is
required, not optional, and it must be installed **before** any namespace is
labelled. Labelling first leaves pods that are neither captured nor reachable.

AKS nodes need `NET_ADMIN` and `NET_RAW`, which the CNI requires:

```bash
az aks update -g rg-ecommerce-prod -n aks-ecommerce-prod \
  --node-osdisk-type Managed
```

### 5.2 Install the control plane and ztunnel

```bash
istioctl install --set profile=ambient -y
```

This installs `istiod`, `istio-cni` and the `ztunnel` DaemonSet. Verify the
DaemonSet is one pod per node and not `0/3`:

```bash
kubectl -n istio-system get ds ztunnel
kubectl -n istio-system logs -l app=ztunnel --tail=20
```

### 5.3 Gateway API CRDs

Waypoints are Kubernetes Gateways, and the CRDs are not installed by default.
Use the **experimental** channel: the resilience `HTTPRoute`s in this repo use
the `retry` field, which is an extended-channel API.

```bash
kubectl apply --server-side -f \
  https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.2.1/experimental-install.yaml
```

### 5.4 The edge: how AGC enters a STRICT mesh

This is the one part of the design that needs explaining, because the
constraint is not obvious.

The namespace runs `PeerAuthentication: STRICT`. **Application Gateway for
Containers runs its data plane outside the cluster.** It has no SPIFFE identity,
does not speak HBONE, and there is no mechanism to give it either. So:

* AGC's traffic to `api-gateway` is refused by `STRICT`, and no
  `AuthorizationPolicy` can rescue it. An unauthenticated peer cannot be
  *authorized*, only tolerated.
* AGC's built-in Istio integration would have solved this automatically, but it
  **explicitly does not support ambient mode** — it assumes sidecars and injects
  one. It is unusable here.

So `api-gateway`'s serving port is carved out to `PERMISSIVE`:

```yaml
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata:
  name: api-gateway-edge-port
spec:
  selector:
    matchLabels:
      app.kubernetes.io/name: api-gateway
  mtls:
    mode: STRICT
  portLevelMtls:
    8080:
      mode: PERMISSIVE
```

Everything east of the gateway — the whole checkout path — stays `STRICT` and
still refuses plaintext. That is where the trust actually matters. `portLevelMtls`
only takes effect with a `selector`, which is why this is a second policy rather
than a field on the namespace-wide one.

**If you later want the edge hop encrypted and authenticated too**, the
supported path is to put an Istio ingress gateway (`gatewayClassName: istio`)
between AGC and the application, and point the `HTTPRoute` at it instead of at
`api-gateway`. That gateway has a real mesh identity, so `STRICT` can apply to
the edge as well. It costs one more L7 hop.

### 5.5 Label the namespace and deploy the waypoint

Order matters. Follow it exactly; the failure mode is traffic processed by
neither proxy.

```bash
# 1. Namespace into ambient (L4 everywhere).
kubectl label namespace ecommerce istio.io/dataplane-mode=ambient

# 2. Deploy the waypoint and wait for it to be ready.
istioctl waypoint apply -n ecommerce --name waypoint
kubectl -n ecommerce get gateway waypoint   # READY must be True

# 3. Enroll destinations (the chart does this per Service via
#    `istio.io/use-waypoint`; do it by hand only for a dry run):
kubectl -n ecommerce label service order-service istio.io/use-waypoint=waypoint
kubectl -n ecommerce label service payment-service istio.io/use-waypoint=waypoint
kubectl -n ecommerce label service inventory-service istio.io/use-waypoint=waypoint
```

Confirm ztunnel now owns the namespace's workloads:

```bash
istioctl ztunnel-config workloads -n istio-system | grep ecommerce
```

Every workload should report `HBONE`. `istioctl proxy-status` does **not** show
ztunnel workloads — use `ztunnel-config` for that.

### 5.6 Verify

```bash
# Pods run with ONE container. If this prints two, a sidecar was injected.
kubectl -n ecommerce get pods -o jsonpath='{.items[*].spec.containers[*].name}' | tr ' ' '\n' | sort | uniq -c

# The waypoint is up and enrolled services are bound to it.
kubectl -n ecommerce get gateway waypoint -o jsonpath='{.status.conditions}'
kubectl -n ecommerce get svc -o custom-columns=NAME:.metadata.name,WAYPOINT:.metadata.labels.istio\.io/use-waypoint

# A pod from OUTSIDE the mesh must not be able to reach a meshed service.
kubectl -n istio-system run test --rm -it --image=curlimages/curl --restart=Never \
  -- curl -sS --max-time 5 http://order-service.ecommerce.svc.cluster.local:8082/actuator/health
```

That last call **must fail**. If it succeeds, the service is not in the mesh.

Confirm the health probes still work, which is the other thing that breaks
silently:

```bash
kubectl -n ecommerce get pods
# Every pod must be 1/1 Running. A stuck 0/1 means the NetworkPolicy is
# blocking ambient's link-local probe address.
```

### 5.7 Optional: mesh tracing

The chart renders mesh access logging by default and only renders tracing when
you name a provider, because an unknown provider leaves the resource rejected.
The provider definition lives in
[`deploy/istiod/meshconfig-tracing.yaml`](../deploy/istiod/meshconfig-tracing.yaml)
and must be applied to the control plane **first**:

```bash
helm upgrade istiod istio/istiod -n istio-system \
  -f deploy/istiod/meshconfig-tracing.yaml
```

`values-prod.yaml` already sets `istio.tracingProvider: otel-collector`, so the order
matters: install the chart before the control plane knows the provider and
istioctl rejects the `Telemetry` resource, taking the access logging on that
same object down with it.

The sampling rate is taken from `global.tracingSampleRate` so the proxy half and
the Micrometer half of a trace are not half-present. See
[`deploy/istiod/README.md`](../deploy/istiod/README.md) for what mesh tracing
does and does not cover — ztunnel never emits spans, and neither `api-gateway`
nor `bff-service` has a waypoint.

## 6. Deploy the application

```bash
# Render and inspect first. Never install a chart you have not read output from.
helm lint deploy/helm/enterprise-ecommerce
helm template ecommerce deploy/helm/enterprise-ecommerce \
  -f deploy/helm/values-prod.yaml | less

# Install
helm upgrade --install ecommerce deploy/helm/enterprise-ecommerce \
  --namespace ecommerce --create-namespace \
  -f deploy/helm/values-prod.yaml \
  --wait --timeout 15m
```

`--wait` matters: it blocks until every Deployment reports ready, so a failed
rollout is visible immediately rather than after a manual check.

Database migrations run on startup via Flyway. With more than one replica, the
first pod to win takes the Flyway lock and the others wait, which is correct but
slows the rollout. For large fleets, run migrations as a separate Helm hook or an
AKS job rather than per-pod.

## 7. Verify

```bash
kubectl -n ecommerce get deploy,pods,svc
kubectl -n ecommerce get hpa,pdb
kubectl -n ecommerce describe pod -l app.kubernetes.io/name=order-service

# Confirm probes are wired to the right port, especially for the headless worker
kubectl -n ecommerce get pod -l app.kubernetes.io/name=notification-service \
  -o jsonpath='{.items[0].spec.containers[0].ports}'
```

Smoke test through the ingress:

```bash
curl -fsS https://api.enterprise-ecommerce.com/actuator/health
curl -fsS https://api.enterprise-ecommerce.com/api/products?size=5
```

The second call should be anonymous; `/api/cart` must return 401 without a token.
If it does not, tenant resolution or the gateway filter chain is misconfigured.

## Rollouts and rollbacks

A progressive rollout is a weighted `HTTPRoute` plus two Services, not a
`VirtualService` with DestinationRule subsets. Enable it in values:

```yaml
services:
  order-service:
    canary:
      enabled: true
      weight: 5          # percent to the canary pool
      label: canary
```

That renders `order-service-stable` and `order-service-canary` (both selecting
on `mesh.enterprise-ecommerce.io/rollout`) and splits the waypoint's backends
by weight. To cut a canary, copy the Deployment and change one label:

```bash
kubectl -n ecommerce deploy \
  deploy/ecommerce-order-service --name ecommerce-order-service-canary
kubectl -n ecommerce patch deploy ecommerce-order-service-canary -p \
  '{"spec":{"template":{"metadata":{"labels":{"mesh.enterprise-ecommerce.io/rollout":"canary"}}}}}'
```

Check the split is real rather than assumed:

```bash
kubectl -n ecommerce get httproute order-service-resilience -o yaml
# istioctl waypoint proxy-config route ecommerce -n ecommerce --name 8082 -o json | jq '.[]?.virtualHosts'
```

Promote by pointing `weight` at 100 on the canary and rolling normally; roll
back by removing the canary Deployment or flipping the weight to 0. Note the
weight is applied **per request** for ingress traffic but the split between two
rollout Services is per connection for east-west traffic, so a long-lived client
pool approaches the configured ratio only as connections are recycled.

```bash
# Roll back the whole release
helm rollback ecommerce <revision>
```

Because Deployments use `maxUnavailable: 0`, capacity never dips during a
rollout. `terminationGracePeriodSeconds: 45` lets in-flight checkouts finish and
release their stock holds, which prevents phantom out-of-stock items on deploy.

## Scaling

HPA reacts on CPU and memory with a fast scale-up and a 300-second scale-down
stabilisation window. Scale-up is aggressive because a traffic spike is the case
you regret being slow for; scale-down is slow because flapping costs more than
briefly idle replicas.

Kafka consumers are deliberately excluded from the HPA. Consumer concurrency is
capped by partition count, so adding replicas beyond the partition count adds
idle pods rather than throughput. Scale those by raising partition count.

## Cost

The floor is roughly 30 pods across 11 services. On `Standard_D4s_v5` with the
requests in `values.yaml`, expect ~28 vCPU and ~60Gi of requests in steady state.
Lower `replicas` for non-production tiers, or set `enabled: false` on the
personalisation tier entirely.

Under sidecar mode that figure included ~40MB and a slice of CPU per pod for the
proxy that lived inside it. It no longer does. What it is replaced by:

| Item | Scale | Notes |
|---|---|---|
| `ztunnel` | 1 per **node** | Not per replica, and not charged to the namespace ResourceQuota. Raise with the node pool, not with traffic. |
| `istio-cni` | 1 per node | A chained CNI agent. Needs `NET_ADMIN`/`NET_RAW`. |
| waypoint | `istio.waypoint.replicas` | **Per namespace**, shared by every enrolled service. Two replicas is the sensible floor; this is the number to tune, because it is the only per-request compute the mesh adds. |

So the mesh's marginal cost is now per node plus two pods, instead of one proxy
per application replica. At the `maxReplicas: 20` HPA ceiling on the checkout
path that is the difference between paying for up to 20 Envoy instances and
paying for two.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Pods `Pending`, no events | Insufficient quota; check the ResourceQuota and node pool |
| Readiness probe failing | Dependency is down; `describe pod` shows the failing check |
| Flapping restarts | Memory limit too low, or a slow dependency blocking startup |
| 503 from the gateway | Circuit breaker open; check the dependency's readiness |
| `401` on an authenticated route | JWT key path wrong, or the tenant claim is missing |
| Timeouts after enabling NetworkPolicy | DNS egress not allowed; confirm `allow-dns-egress` exists |
| Image pull failure | ACR attach missing, or workload identity not mapped |

### Ambient-specific failures

These present as ordinary network errors and are almost always one of a small
number of configuration mistakes.

| Symptom | Cause | Fix |
|---|---|---|
| Pods stuck `0/1`, `describe` shows nothing wrong | `default-deny` is blocking ambient's link-local probe address | Confirm `allow-health-probes` exists with **both** the IPv4 and IPv6 `ipBlock` |
| East-west calls hang, probes fine | `NetworkPolicy` does not allow port 15008 | Add 15008 to the ingress rules; ambient terminates HBONE there before the app port |
| Waypoint-delivered calls time out | Namespace `default-deny` egress blocks the pod → waypoint hop | Confirm `allow-waypoint-egress` exists and selects on `gateway.networking.k8s.io/gateway-name` |
| **All** traffic to one service blocked | A selector-targeted `AuthorizationPolicy` carries `paths`/`methods`; ztunnel converts it to a blanket DENY | Move the rule to `targetRefs` (attached to the waypoint) — see [Istio.md](Istio.md) §3 |
| Service silently fell back to L4 | Waypoint not ready, or traffic type mismatch (`waypoint-for: service` but traffic addressed to a pod IP) | `kubectl -n ecommerce get gateway waypoint` must be `READY=True` |
| `proxy-status` shows nothing | It does not list ztunnel workloads | Use `istioctl ztunnel-config workloads` |
| Metrics dashboards empty after migration | No metrics merging in ambient; `reporter` label is `waypoint`, not `destination` | Scrape ztunnel and waypoint pods as separate targets |

### AGC-specific failures

| Symptom | Cause | Fix |
|---|---|---|
| **Every request 503s from the edge** | `apiGateway.backendCidrs` does not match the source address AGC presents. This is the single most likely failure after the migration | Find the real source address from the gateway's access log, then set it in both `apiGateway.backendCidrs` and the `gateway-edge-only` NetworkPolicy |
| Same 503, CIDR already correct | `PeerAuthentication/api-gateway-edge-port` missing, so `STRICT` refuses AGC's plaintext | Confirm the port carve-out exists and targets the workload port, not the Service port |
| Gateway `Accepted: True` but no address | The `ApplicationLoadBalancer` resource or BYO `alb-id` does not resolve | Check `alb.networking.azure.io/alb-name`/`alb-namespace`, and that the subnet is delegated to `Microsoft.Network/applicationGateways` |
| Listener never reaches `Programmed` | The certificate Secret is missing, or is `Opaque` rather than `kubernetes.io/tls` | Check the SecretProviderClass synced and its `type` |
| 502 with the AGC backend "unhealthy" | Azure Direct Server Return: kube-proxy SNAT breaks response routing | Set `externalTrafficPolicy: Local` on the backend Service if AGC is fronting an internal load balancer |
| ALB Controller pods crash-looping | Missing Key Vault access for its managed identity | Verify the identity has the AppGW for Containers Configuration Manager role |
| Helm install fails with `GatewayClass` not found | ALB Controller not installed, or Gateway API CRDs missing | `kubectl get gatewayclass azure-alb-external` |
| AGC deployed but nothing routes | Ingress API resources still present and being served by a retired controller | Delete the old `Ingress` objects; the chart no longer renders any |
