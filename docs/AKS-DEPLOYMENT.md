# Deploying to Azure Kubernetes Service

Covers provisioning a cluster, wiring managed data services, and shipping the
mesh to production.

## Prerequisites

- Azure CLI 2.60+ and `az aks` extension
- `kubectl` v1.34+ (matches the tested AKS version)
- Helm 3.15+
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

## 2. Managed data services

Self-hosted databases on nodes are a single failure domain. In production use:

| Purpose | Azure service | Notes |
|---|---|---|
| PostgreSQL | Azure Database for PostgreSQL Flexible Server | Zone-redundant HA, one database per service |
| Cache | Azure Cache for Redis Premium | Cluster mode, eviction policy `allkeys-lru` |
| Broker | Azure Service Bus (Premium) or Event Hubs | See the Kafka note below |
| Catalogue / reviews / AI | Azure Cosmos DB for MongoDB | Wire-compatible with the Mongo driver |
| Secrets | Azure Key Vault | Mounted via the CSI driver |

On AKS, prefer the **Azure Service Bus Kafka-compatible endpoint** with Spring
Kafka over running Kafka in the cluster: it removes broker state from the cluster
and gives you partition/reserved-throughput guarantees. Set:

```yaml
infrastructure:
  kafka:
    bootstrapServers: <namespace>.servicebus.windows.net:9093
```

If you must run Kafka in-cluster, use a StatefulSet with a node-local Persistent
Volume per broker. Replication factor 3 across availability zones is required
before production traffic; the chart's default topic config already sets
`min.insync.replicas: 2`, which will refuse to produce if that is not met.

## 3. Key Vault and the CSI driver

Install the secrets-store CSI driver and enable secret rotation:

```bash
az aks enable-addons --resource-group rg-ecommerce-prod --name aks-ecommerce-prod \
  --addon secrets-store

kubectl -n ecommerce apply -f deploy/k8s/keyvault-secretproviderclass.yaml
```

The chart's `keyVault.enabled: true` mounts those secrets at `/mnt/secrets`.
The JWT verification key in particular is read from
`/mnt/secrets/jwt/public-key.pem`, so rotating it is a Key Vault operation plus a
pod restart, not a redeploy of secrets through Helm.

## 4. Istio

```bash
helm repo add istio https://istio-release.storage.googleapis.com/charts
helm install istio-base istio/base -n istio-system --create-namespace
helm install istiod istio/istiod -n istio-system
helm install istio-gateway istio/gateway -n istio-system \
  --set labels.istio-injection=enabled

# Optional: a dedicated ingress gateway for the public edge
helm install istio-ingress istio/gateway -n istio-system \
  --set labels.app=istio-ingressgateway \
  --set service.type=LoadBalancer
```

The chart renders `PeerAuthentication` in `STRICT` mTLS mode, a deny-by-default
`AuthorizationPolicy`, and an egress allow-list. Verify after install:

```bash
kubectl -n ecommerce get peerauthentication,authorizationpolicy
# A pod outside the mesh should fail to reach an in-mesh service:
kubectl -n ecommerce run test --rm -it --image=curlimages/curl --restart=Never \
  -- curl -sS --max-time 5 http://order-service:8082/actuator/health
```

If that call succeeds, mTLS is not actually strict and the policy has a gap.

## 5. Deploy the application

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

## 6. Verify

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

```bash
# Canary 5% of checkout traffic using the Istio subsets
kubectl -n ecommerce apply -f deploy/k8s/canary-patch.yaml

# Roll back
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
