# Azure observability resources

Azure-side resources that **cannot** be expressed as Kubernetes objects, and so
cannot be created by the Helm chart. Same reasoning as
[`deploy/bicep`](../bicep/README.md) and [`deploy/istiod`](../istiod/README.md).

> **Status: specification, not tested code.**
> These are CLI commands and resource shapes written from Microsoft's documented
> behaviour. They have **not** been executed against a subscription — no Azure
> credentials were available where they were written. Treat the resource names,
> API versions and property names as needing a first-run check, and expect the
> OTLP wiring in particular to need adjustment. Nothing here is wired into CI.

---

## Target state

| Signal | Backend | Replaces |
|---|---|---|
| Metrics | Azure Monitor managed service for Prometheus (workspace-based) | self-hosted Prometheus in-cluster |
| Dashboards | Azure Managed Grafana | self-hosted Grafana + `deploy/observability/grafana/` |
| Traces | Application Insights (OTLP ingest) | Jaeger |
| Logs | Log Analytics, via the Application Insights OTLP logs endpoint | *nothing — there was no log backend* |
| Alerting | Azure Monitor Prometheus rule groups + action groups | *nothing — there was no Alertmanager* |

Local development keeps its own Prometheus / Grafana / Jaeger via
`docker-compose.yml`. That is unchanged and still works.

---

## 1. Azure Monitor workspace

Storage for managed Prometheus. Everything else attaches to this.

```bash
az monitor workspace create \
  --resource-group rg-ecommerce-prod \
  --workspace-name amw-ecommerce-prod \
  --location <location>
```

> **Gotcha worth knowing before you look for your old data.** Since the AKS
> versions that shipped managed Prometheus, Prometheus metrics are stored *in this
> workspace*, not in a classic Log Analytics workspace. The `Perf` and
> `InsightMetrics` tables you would query for container metrics are not there.
> Queries written against those tables return nothing after the move, with no
> error.

## 2. Enable managed Prometheus on the cluster

```bash
az aks update \
  --resource-group rg-ecommerce-prod \
  --name <aks-cluster> \
  --enable-azure-monitor-metrics \
  --azure-monitor-workspace-resource-id <amw-resource-id>
```

This deploys `ama-metrics`, `ama-metrics-ksm` and `ama-metrics-node` into
`kube-system`. `ama-metrics-ksm` supplies the `kube_*` series that the
self-hosted stack could not produce — which is why
`deploy/bicep/alerting.bicep` can restore `PodCrashLooping`,
`NoHealthyReplicas` and a corrected `MemoryPressureHigh`.

Retention is 18 months, platform-managed.

## 3. Application Insights, with OTLP enabled

This is what replaces Jaeger, and it also ingests logs — which closes the gap
where structured ECS logs had nowhere to go.

```bash
az monitor app-insights component create \
  --resource-group rg-ecommerce-prod \
  --app <appi-ecommerce-prod> \
  --workspace <amw-ecommerce-prod> \
  --retention 30
```

Then **enable OTLP support**, which is a separate step from creating the
resource. Microsoft documents this as a portal toggle on the Basics tab
("OTLP support: On"); the CLI surface for it has moved between versions, so
verify against current docs rather than trusting a command copied from here.

Once enabled the component exposes three OTLP endpoints (traces, metrics, logs)
which must be read from the component's OTLP connection info in the portal.

### How the credential is delivered

Azure Monitor's OTLP ingest path is **authenticated**. Traffic must carry the
component's connection string (or an Entra token), and the endpoint is a public
`*.azure.com` host over 443 — not a cluster-internal address.

Pointing every application at it directly would mean putting that credential in
nine Deployments and in istiod. The repository therefore runs an OpenTelemetry
Collector in the `observability` namespace
(`deploy/observability/otel-collector/`), and only the collector holds it:

```
application pods ──OTLP/HTTP :4318──┐
waypoint (Istio) ──OTLP/gRPC :4317──┴──► otel-collector ──azuremonitor exporter──► Application Insights
```

- `global.otlpEndpoint` and every `management.otlp.tracing.endpoint` default
  point at `otel-collector.observability.svc.cluster.local:4318`.
- `deploy/istiod/meshconfig-tracing.yaml` defines the `otel-collector` provider
  on gRPC `:4317`. Istio's extension provider has no header mechanism, which is
  precisely why it cannot target Application Insights itself.
- The connection string lives in the `appinsights-connection-string` Secret in
  the `observability` namespace, read by the collector as
  `AZURE_MONITOR_CONNECTION_STRING`.

Deploy that directory after creating Application Insights. A missing Secret
leaves the collector pod in `CreateContainerConfigError`, which is the correct
failure — spans are not silently dropped.

## 4. Azure Managed Grafana

```bash
az grafana create \
  --resource-group rg-ecommerce-prod \
  --name agf-ecommerce-prod \
  --location <location>
```

Then add the managed Prometheus datasource pointing at the workspace's query
endpoint. Managed Grafana can read a workspace-backed Prometheus directly; the
datasource UID it creates is not known in advance, so the connection is best
done in the portal or via the resource API rather than by a provisioning file
that guesses a UID.

**Migration item:** `deploy/observability/grafana/provisioning/` and the
`mesh-overview.json` dashboard are provisioning files for a *self-hosted* Grafana.
They do not carry over. The dashboard's PromQL needs re-authoring against the
AMP datasource, and `reporter` labels must read `waypoint`, not `destination`.

## 5. Action groups and Prometheus rule groups

This is the step that makes the rules real. Two separate Azure resources are
needed, and neither is a Kubernetes object:

| Resource | Type | Why |
|---|---|---|
| Action groups | `Microsoft.Insights/actionGroups` | Delivery: email, SMS, voice, webhook |
| Rule group | `Microsoft.AlertsManagement/prometheusRuleGroups` | AMP evaluates this, not `PrometheusRule` |

`deploy/observability/amp/rules.yaml` is a readable catalogue of the PromQL.
It is **not** applied by `kubectl`: Azure Monitor managed Prometheus does not
evaluate `PrometheusRule` CRDs. The deployable form is
[`deploy/bicep/alerting.bicep`](../bicep/alerting.bicep), which creates both
action groups and the rule group, and attaches the right action group to every
rule.

```bash
az deployment group create \
  --resource-group rg-ecommerce-prod \
  --template-file deploy/bicep/alerting.bicep \
  --parameters azureMonitorWorkspaceId=<amw-resource-id> \
               aksClusterId=<aks-resource-id> \
               criticalEmails='["oncall@example.com"]' \
               criticalSms='[{"countryCode":"1","phoneNumber":"+15555550100"}]' \
               warningEmails='["platform@example.com"]'
```

Critical rules page (email + SMS/voice), warning rules go to email/business
hours — the split `docs/RUNBOOK.md` describes. Email alone is not a page.

**ACS is optional, and not a native action-group receiver.** Azure Monitor
cannot call Azure Communication Services Email directly. If branded email is a
requirement, put a Logic App or Function in front of the ACS Email API and pass
its URL as `acsWebhookUrl`; the action group then uses a webhook receiver.
Grafana SMTP is irrelevant here because the alerts are Azure Monitor rule
groups, not Grafana-managed alerts.

---

## 6. In-cluster configuration

Everything Kubernetes-side lives in [`deploy/observability/amp/`](../observability/amp/):

| File | Contents |
|---|---|
| `kustomization.yaml` | Apply entry point for the Kubernetes-side scrape objects |
| `podmonitors.yaml` | Scrape targets for the twelve services, the headless management port, and the waypoint |
| `rules.yaml` | Readable catalogue of the PromQL; deployed by `deploy/bicep/alerting.bicep`, not by `kubectl` |

```bash
kubectl apply -f deploy/observability/amp/
```

This applies the `PodMonitor` resources only. The rule group is an Azure
resource; deploy it with `alerting.bicep` as shown in section 5.

Note what these **remove** a need for. The previous in-cluster NetworkPolicies
granted nothing to an `observability` namespace, so a self-hosted Prometheus
would have been blocked by `default-deny-all` on both scrape and egress. Managed
Prometheus runs its agent from `kube-system` and is wired up by Azure, which
sidesteps that entirely — one of the cleaner wins in this migration.

---

## Order of operations

Each step depends on the one before it, and two of them fail silently if
skipped.

1. Azure Monitor workspace
2. `az aks update --enable-azure-monitor-metrics`
3. Application Insights + **enable OTLP** + resolve the auth-header question
4. `kubectl apply -f deploy/observability/amp/` (PodMonitors only)
5. Managed Grafana + datasource
6. `az deployment group create ... alerting.bicep` (action groups + rule group)

**Verify after step 4** that metrics are actually arriving before building
anything on top of them:

```bash
kubectl -n ecommerce get podmonitor
kubectl -n kube-system get pods -l app=ama-metrics
```

A PodMonitor with no endpoints means the add-on is not on or the selector is
wrong. After step 6, verify the rule group itself:

```bash
az monitor prometheus rule-group show \
  --resource-group rg-ecommerce-prod \
  --name ecommerce-prometheus-rules
```

An empty rule list, or a rule with no `actions`, means the alert evaluates but
nobody is notified.
