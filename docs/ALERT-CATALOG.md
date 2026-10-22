# Alert Catalog — enterprise-ecommerce-mesh

Reference for alert priority, routing, and implementation status.

| Source of truth | Location |
|---|---|
| Deployed rules | [`deploy/bicep/alerting.bicep`](../deploy/bicep/alerting.bicep) |
| Readable PromQL catalogue | [`platform/observability/amp/rules.yaml`](../platform/observability/amp/rules.yaml) |
| Runbooks | [`docs/RUNBOOK.md`](RUNBOOK.md) |
| BFF session notes | [`docs/BFF-SESSION.md`](BFF-SESSION.md) |

`rules.yaml` is a reference only. Azure Monitor managed Prometheus does not
evaluate `PrometheusRule` objects; `alerting.bicep` creates the
`Microsoft.AlertsManagement/prometheusRuleGroups` resource that actually runs.

---

## 1. Priority model

The runbook uses `critical` / `warning`. This catalog adds an operational
priority layer on top so routing is unambiguous.

| Priority | Operational meaning | Azure severity | Page? | Response |
|---|---|---|---|---|
| **P0** | Customer-visible outage, lost revenue, data loss, or no ability to detect failures | `0` | Yes, 24/7 | Immediate |
| **P1** | Degradation with a workaround, or rising risk | `2` | No | Same business day |
| **P2** | Trend, capacity, cost, compliance, or hygiene | `3` / no rule | No | Weekly review |

Mapping:

- **P0 = `critical`** — page the on-call.
- **P1 = `warning`** — email and Teams during business hours.
- **P2 = `info`** — email digest or dashboard only. Do not page.

---

## 2. Notification routing

| Priority | Primary channel | Secondary channel | Current implementation |
|---|---|---|---|
| **P0** | Email + SMS to the on-call | Voice call, Teams mention, or incident tool | `ag-ecommerce-critical`: email + SMS + optional ACS webhook. Add voice/Teams if required. |
| **P1** | Email to the owning team's distribution list | Teams channel | `ag-ecommerce-warning`: email + optional ACS webhook. Add a Teams receiver if required. |
| **P2** | Email digest / dashboard | Ticket or weekly review | No Azure action group by default. Use a distribution list or scheduled report. |

### Email recipients

| Priority | Who receives it | Why |
|---|---|---|
| **P0** | On-call engineer, engineering lead, incident mailbox | Needs an immediate human response |
| **P1** | Platform team, service owner, QA lead | Needs a same-day fix, not a 03:00 wake-up |
| **P2** | Engineering managers, FinOps, security/compliance | Weekly trend and budget review |

### ACS and Teams notes

- Azure Monitor action groups have **no native ACS Email receiver**. The optional
  `acsWebhookUrl` must point to a Logic App or Function that calls the ACS Email
  API. Native email + SMS is the simpler baseline.
- ACS is not a paging service. P0 still needs SMS or voice; email can arrive late.
- Grafana SMTP is irrelevant here because these are Azure Monitor rule groups,
  not Grafana-managed alerts.
- Teams requires an incoming-webhook URL; add it as an additional webhook
  receiver in the action group.

---

## 3. P0 — page immediately

| # | Alert | Condition | Signal / source | Notification | Status |
|---|---|---|---|---|---|
| 1 | `ServiceHighErrorRate` | 5xx ratio > 5% for 10m | `service:error_ratio:rate5m` | Email + SMS (+ Teams) | **Live** |
| 2 | `CheckoutConversionCollapse` | Checkout success ratio < 80% for 15m, with order traffic | `checkout:success_ratio:rate5m` | Email + SMS (+ Teams) | **Live** |
| 3 | `NoHealthyReplicas` | A deployment has 0 available replicas for 5m | KSM `kube_deployment_status_replicas_available` | Email + SMS (+ Teams) | **Live** |
| 4 | `KubeNodeUnreachable` | A node is NotReady for 15m | KSM `kube_node_status_condition` | Email + SMS (+ Teams) | **Live** |
| 5 | `OrderPipelineStalled` | No fresh order-pipeline heartbeat for 15m during business hours | New heartbeat/freshness metric | Email + SMS (+ Teams) | **Proposed** |
| 6 | `PaymentProviderOutage` | Provider error ratio > 10% for 10m | New `payment_provider_errors_total` metric | Email + SMS (+ Teams) | **Proposed** |
| 7 | `DatabaseUnavailable` | Database unavailable, or Resource Health `Unavailable` | Azure platform metric / Resource Health | Email + SMS (+ Teams) | **Proposed — platform alert** |
| 8 | `EdgeSyntheticFailure` | Public edge check fails from 2+ locations for 5m | Azure Monitor availability test / Grafana Synthetic Monitoring | Email + SMS (+ Teams) | **Proposed — synthetic test** |
| 9 | `TLSCertExpiringCritical` | Edge certificate expires in < 14 days, or is expired | Key Vault certificate event / cert-manager metric | Email + SMS (+ Teams) | **Proposed** |
| 10 | `AlertingPipelineDead` | Watchdog heartbeat missing for > 10m | External heartbeat (Healthchecks.io, incident tool) | Email + SMS (+ Teams) | **Proposed — dead-man's switch** |

> `KafkaConnectTaskFailed` is deliberately **P1**, not P0. The end-to-end
> freshness alert (`OrderPipelineStalled`) pages; the connector alert tells you
> where to look. Two P0 pages for one failure train people to ignore both.

---

## 4. P1 — email and Teams, business hours

| # | Alert | Condition | Signal / source | Notification | Status |
|---|---|---|---|---|---|
| 1 | `ServiceLatencyBreached` | p95 latency > 1s for 15m | `service:latency:p95` | Email (+ Teams) | **Live** |
| 2 | `InventoryLockContention` | Optimistic-lock retries > 0.5/s for 10m | `inventory_concurrent_update_total` | Email (+ Teams) | **Live** |
| 3 | `KafkaConsumerLagHigh` | Consumer lag > 10,000 for 15m | `kafka_consumer_fetch_manager_records_lag` | Email (+ Teams) | **Live** |
| 4 | `DeadLetterQueueFilling` | Dead-letter rate > 0.1/s for 10m | `messaging_dead_letter_total` | Email (+ Teams) | **Live** |
| 5 | `PodCrashLooping` | Any container restart in 10m, sustained 5m | KSM `kube_pod_container_status_restarts_total` | Email (+ Teams) | **Live** |
| 6 | `MemoryPressureHigh` | Working set > 90% of limit for 15m | cAdvisor + KSM | Email (+ Teams) | **Live** |
| 7 | `KubeContainerOOMKilled` | Last termination reason was `OOMKilled` for 5m | KSM terminated-reason metric | Email (+ Teams) | **Live** |
| 8 | `KubeDeploymentReplicasMismatch` | Available replicas differ from desired for 10m | KSM deployment metrics | Email (+ Teams) | **Live** |
| 9 | `KubeHpaMaxedOut` | HPA is at max replicas for 15m | KSM HPA metrics | Email (+ Teams) | **Live** |
| 10 | `BFFRefreshRejected` | Refresh rejection rate > 0.1/s for 10m | `mesh_business_events_total{domain="bff_session",outcome="refresh_rejected"}` | Email (+ Teams) | **Live** |
| 11 | `BFFNoRefreshToken` | Any `no_refresh_token` in 10m, sustained 5m | `mesh_business_events_total{domain="bff_session",outcome="no_refresh_token"}` | Email (+ Teams) | **Live** |
| 12 | `KafkaConnectTaskFailed` | Connector or task not `RUNNING` for 5m | Kafka Connect / Debezium exporter | Email (+ Teams) | **Proposed** |
| 13 | `KubePersistentVolumeFillingUp` | PVC < 10% free for 15m | `kubelet_volume_stats_*` | Email (+ Teams) | **Proposed** |
| 14 | `KubeNodePressure` | MemoryPressure, DiskPressure, or PIDPressure for 15m | KSM `kube_node_status_condition` | Email (+ Teams) | **Proposed** |
| 15 | `ImagePullBackOff` | Container waiting on image pull for 10m | KSM container waiting reason | Email (+ Teams) | **Proposed** |
| 16 | `RolloutStuck` | Observed generation ≠ desired generation for 15m | KSM deployment metrics | Email (+ Teams) | **Proposed** |
| 17 | `DatabaseCPUHigh` | Database CPU > 80% for 15m | Azure platform metric | Email (+ Teams) | **Proposed — platform alert** |
| 18 | `DatabaseStorageHigh` | Database storage > 80% for 15m | Azure platform metric | Email (+ Teams) | **Proposed — platform alert** |
| 19 | `BackupFailed` | Backup job failed, or no successful backup in 24h | Azure Backup / platform | Email (+ Teams) | **Proposed — platform alert** |
| 20 | `TLSCertExpiringWarning` | Edge certificate expires in < 30 days | Key Vault / cert-manager | Email (+ Teams) | **Proposed** |
| 21 | `LogIngestionStopped` | No ContainerLogV2 rows for 10m, or agent unhealthy | Log Analytics / Container Insights | Email (+ Teams) | **Proposed — platform alert** |

### Checkout step failures

`CheckoutConversionCollapse` already catches the aggregate drop. The
`orders` metric now also records `confirm_failed` and `commit_failed`, so a
dedicated P1 rule can be added later if you want the failing step called out
without opening the dashboard:

```promql
sum(increase(mesh_business_events_total{domain="orders",outcome=~"confirm_failed|commit_failed"}[10m])) > 0
```

---

## 5. P2 — email digest or dashboard only

| # | Alert / report | Condition | Notification | Status |
|---|---|---|---|---|
| 1 | `TLSCertExpiringNotice` | Edge certificate expires in < 60 days | Email digest | **Proposed** |
| 2 | `BudgetThreshold` | 80% and 100% of monthly Azure budget | Email + FinOps ticket | **Proposed — Cost Management** |
| 3 | `LogDailyCapReached` | Log Analytics daily cap reached | Email digest | **Proposed — platform alert** |
| 4 | `CapacityTrendHigh` | Node CPU or memory > 80% for 7 days | Weekly digest | **Proposed** |
| 5 | `BackupRestoreTestOverdue` | Monthly restore test not completed | Email digest | **Proposed** |
| 6 | `BFFAnonymousSpike` | Anonymous session share or rate anomalous vs 7-day baseline | Dashboard / digest | **Proposed** |
| 7 | `BFFRefreshDisabled` | `refresh_disabled` events appear | Dashboard / digest | **Proposed** |
| 8 | `PDBCurrentHealthyLow` | Healthy pods < desired for 15m | Email / dashboard | **Proposed** |
| 9 | `DeprecatedAPIUsage` | Deprecated Kubernetes API calls detected | Weekly report | **Proposed** |
| 10 | `WeeklySLOReport` | Error budget, availability, latency, checkout conversion | Weekly email | **Proposed** |

P2 is deliberately not wired to a paging action group. If it pages, it is P1.

---

## 6. What is live today

| Priority | Live | Proposed |
|---|---|---|
| **P0** | 4 | 6 |
| **P1** | 11 | 10 |
| **P2** | 0 | 10 |

The live rules are in `deploy/bicep/alerting.bicep`:

- 6 recording rules.
- 15 alert rules.
- 2 action groups: `ag-ecommerce-critical` (email + SMS) and
  `ag-ecommerce-warning` (email).
- 4 P0 rules: `ServiceHighErrorRate`, `CheckoutConversionCollapse`,
  `NoHealthyReplicas`, `KubeNodeUnreachable`.
- 11 P1 rules: the warning rules listed above.

### The gaps that matter most

1. **Order pipeline freshness** — Kafka consumer lag does not detect a stopped
   producer. Add a heartbeat or freshness metric before claiming the order
   pipeline is monitored.
2. **Payment provider errors** — without a provider-specific metric you only see
   checkout conversion drop, not the cause.
3. **Database availability and capacity** — Azure platform alerts, not PromQL.
4. **Public edge synthetic check** — a Prometheus rule inside the cluster cannot
   prove the public edge is reachable.
5. **TLS expiry** — Key Vault or cert-manager, not a generic HTTP rule.
6. **Alerting dead-man's switch** — an alert that never fires looks the same as
   a healthy system. The watchdog must be external.

---

## 7. Silence and maintenance rules

- **P0 silences** require an incident or change ID and an expiry time. Never
  silence `CheckoutConversionCollapse` or `ServiceHighErrorRate` for more than
  30 minutes without an active incident.
- **P1/P2 silences** must have an expiry. A silence with no end date is a deleted
  alert.
- During a rollout, silence `PodCrashLooping`, `NoHealthyReplicas`, and
  `KubeDeploymentReplicasMismatch` for the rollout window only.
- If an alert is noisy, fix the threshold or add a traffic guard. Do not leave a
  permanently muted rule in the catalog.

---

## 8. Test checklist

- [ ] P0 test alert reaches the on-call inbox **and** SMS.
- [ ] P1 test alert reaches the owning team's inbox.
- [ ] Optional ACS webhook delivers an email through the Logic App/Function.
- [ ] Optional Teams webhook posts to the incident channel.
- [ ] Every runbook link in the alert annotation resolves.
- [ ] The alert resolves automatically when the condition clears.
- [ ] The dead-man's switch fires when the watchdog heartbeat stops.
- [ ] A silence expires at the configured time.
- [ ] KSM metrics are present; otherwise the Kubernetes rules are silently dead.

---

## 9. Deploy

```bash
kubectl apply -f platform/observability/amp/

az deployment group create \
  --resource-group rg-ecommerce-prod \
  --template-file deploy/bicep/alerting.bicep \
  --parameters azureMonitorWorkspaceId=<amw-id> \
               aksClusterId=<aks-id> \
               criticalEmails='["oncall@example.com"]' \
               criticalSms='[{"countryCode":"1","phoneNumber":"+15555550100"}]' \
               warningEmails='["platform@example.com"]'
```

For ACS email, add `acsWebhookUrl=<logic-app-or-function-url>`. For Teams, add a
webhook receiver to the action group, or use the same bridge pattern.
