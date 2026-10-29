// Azure Monitor alerting for the enterprise e-commerce mesh.
//
// Why this file exists: Azure Monitor managed Prometheus (AMP) does not evaluate
// `PrometheusRule` custom resources. The rules in
// deploy/observability/amp/rules.yaml are a readable catalogue; this file is
// the deployable form. It creates:
//
//   1. A critical action group: email + SMS + optional ACS webhook.
//   2. A warning action group: email + optional ACS webhook.
//   3. One Microsoft.AlertsManagement/prometheusRuleGroups resource containing
//      the recording rules and alert rules, with the action groups attached.
//
// ACS (Azure Communication Services) is not a native Azure Monitor action-group
// receiver. The optional `acsWebhookUrl` is expected to be a Logic App or
// Function endpoint that calls the ACS Email API. If you do not need branded
// email, leave it empty and use the native email receivers.
//
// Deploy:
//   az deployment group create \
//     --resource-group rg-ecommerce-prod \
//     --template-file deploy/bicep/alerting.bicep \
//     --parameters azureMonitorWorkspaceId=<amw-id> \
//                  aksClusterId=<aks-id> \
//                  criticalEmails='["oncall@example.com"]' \
//                  warningEmails='["platform@example.com"]'
//
// The rule group is scoped to both the Azure Monitor workspace and the AKS
// cluster. `clusterName` must match the `cluster` label AMP attaches to the
// series, which defaults to the AKS resource name.

targetScope = 'resourceGroup'

@description('Region for the rule group. Must be a region that supports Azure Monitor managed Prometheus rule groups.')
param location string = resourceGroup().location

@description('Resource ID of the Azure Monitor workspace that stores the metrics.')
param azureMonitorWorkspaceId string

@description('Resource ID of the AKS cluster whose kube-state-metrics and application metrics are evaluated.')
param aksClusterId string

@description('Addresses paged for critical alerts. At least one receiver (email, SMS, or webhook) is required.')
param criticalEmails array = []

@description('SMS receivers for critical alerts. Each item is { countryCode: "1", phoneNumber: "555..." }.')
param criticalSms array = []

@description('Addresses for warning alerts. Warnings are not pages.')
param warningEmails array = []

@description('Optional Logic App or Function URL that sends email through Azure Communication Services.')
@secure()
param acsWebhookUrl string = ''

@description('Name of the Azure Monitor Prometheus rule group.')
param ruleGroupName string = 'ecommerce-prometheus-rules'

@description('Name of the action group used by severity 0 (critical) alerts.')
param criticalActionGroupName string = 'ag-ecommerce-critical'

@description('Name of the action group used by severity 2 (warning) alerts.')
param warningActionGroupName string = 'ag-ecommerce-warning'

var clusterName = last(split(aksClusterId, '/'))

// Short names are limited to 12 characters by Azure Monitor.
resource criticalActionGroup 'Microsoft.Insights/actionGroups@2023-01-01' = {
  name: criticalActionGroupName
  location: 'global'
  properties: {
    groupShortName: 'ecom-crit'
    enabled: true
    emailReceivers: [for (email, i) in criticalEmails: {
      name: 'critical-email-${i}'
      emailAddress: email
      useCommonAlertSchema: true
    }]
    smsReceivers: [for (sms, i) in criticalSms: {
      name: 'critical-sms-${i}'
      countryCode: sms.countryCode
      phoneNumber: sms.phoneNumber
    }]
    webhookReceivers: empty(acsWebhookUrl) ? [] : [
      {
        name: 'acs-email-bridge'
        serviceUri: acsWebhookUrl
        useCommonAlertSchema: true
      }
    ]
  }
}

resource warningActionGroup 'Microsoft.Insights/actionGroups@2023-01-01' = {
  name: warningActionGroupName
  location: 'global'
  properties: {
    groupShortName: 'ecom-warn'
    enabled: true
    emailReceivers: [for (email, i) in warningEmails: {
      name: 'warning-email-${i}'
      emailAddress: email
      useCommonAlertSchema: true
    }]
    webhookReceivers: empty(acsWebhookUrl) ? [] : [
      {
        name: 'acs-email-bridge'
        serviceUri: acsWebhookUrl
        useCommonAlertSchema: true
      }
    ]
  }
}

resource ruleGroup 'Microsoft.AlertsManagement/prometheusRuleGroups@2023-03-01' = {
  name: ruleGroupName
  location: location
  properties: {
    description: 'Availability, latency, consumer health, platform health and checkout conversion for the enterprise e-commerce mesh.'
    scopes: [
      azureMonitorWorkspaceId
      aksClusterId
    ]
    clusterName: clusterName
    interval: 'PT1M'
    enabled: true
    rules: [
      // ---- Recording rules ------------------------------------------------
      {
        record: 'service:http_requests:rate5m'
        expression: 'sum by (application) (rate(http_server_requests_seconds_count{namespace="ecommerce"}[5m]))'
        enabled: true
      }
      {
        record: 'service:http_errors:rate5m'
        expression: 'sum by (application) (rate(http_server_requests_seconds_count{status=~"5..",namespace="ecommerce"}[5m]))'
        enabled: true
      }
      {
        record: 'service:latency:p95'
        expression: 'histogram_quantile(0.95, sum by (application, le) (rate(http_server_requests_seconds_bucket{namespace="ecommerce"}[5m])))'
        enabled: true
      }
      {
        record: 'service:latency:p99'
        expression: 'histogram_quantile(0.99, sum by (application, le) (rate(http_server_requests_seconds_bucket{namespace="ecommerce"}[5m])))'
        enabled: true
      }
      {
        record: 'service:error_ratio:rate5m'
        expression: 'sum by (application) (rate(http_server_requests_seconds_count{status=~"5..",namespace="ecommerce"}[5m])) / clamp_min(sum by (application) (rate(http_server_requests_seconds_count{namespace="ecommerce"}[5m])), 0.001)'
        enabled: true
      }
      {
        // Orders that reached PAID, not merely HTTP 200. A failed saga returns
        // 200 with a FAILED status, so the HTTP error ratio misses it entirely.
        record: 'checkout:success_ratio:rate5m'
        expression: 'sum(rate(mesh_business_events_total{domain="orders",outcome="paid",namespace="ecommerce"}[5m])) / clamp_min(sum(rate(mesh_business_events_total{domain="orders",namespace="ecommerce"}[5m])), 0.001)'
        enabled: true
      }

      // ---- Critical alerts (page) -----------------------------------------
      {
        alert: 'ServiceHighErrorRate'
        expression: 'service:error_ratio:rate5m > 0.05'
        'for': 'PT10M'
        severity: 0
        enabled: true
        labels: {
          severity: 'critical'
          team: 'platform'
          runbook: 'docs/RUNBOOK.md'
        }
        annotations: {
          summary: 'High 5xx rate on {{ $labels.application }}'
          description: '{{ $value | humanizePercentage }} of requests to {{ $labels.application }} are failing for 10 minutes. Check recent deploys and dependency health before scaling.'
        }
        actions: [
          {
            actionGroupId: criticalActionGroup.id
          }
        ]
        resolveConfiguration: {
          autoResolved: true
          timeToResolve: 'PT5M'
        }
      }
      {
        alert: 'CheckoutConversionCollapse'
        expression: '(sum(rate(mesh_business_events_total{domain="orders"}[5m])) > 0) and (checkout:success_ratio:rate5m < 0.8)'
        'for': 'PT15M'
        severity: 0
        enabled: true
        labels: {
          severity: 'critical'
          team: 'checkout'
          runbook: 'docs/RUNBOOK.md#checkout'
        }
        annotations: {
          summary: 'Checkout success rate below 80%'
          description: 'Only {{ $value | humanizePercentage }} of checkouts are completing. This is lost revenue, not just an error rate: check inventory reservation failures and payment declines first.'
        }
        actions: [
          {
            actionGroupId: criticalActionGroup.id
          }
        ]
        resolveConfiguration: {
          autoResolved: true
          timeToResolve: 'PT5M'
        }
      }
      {
        alert: 'NoHealthyReplicas'
        expression: 'max by (namespace, deployment) (kube_deployment_status_replicas_available{namespace="ecommerce"}) == 0'
        'for': 'PT5M'
        severity: 0
        enabled: true
        labels: {
          severity: 'critical'
          team: 'platform'
          runbook: 'docs/RUNBOOK.md#no-healthy-replicas'
        }
        annotations: {
          summary: 'Deployment {{ $labels.deployment }} has no available replicas'
          description: 'Every replica is unavailable. Check the rollout, image pull, and pod events before scaling.'
        }
        actions: [
          {
            actionGroupId: criticalActionGroup.id
          }
        ]
        resolveConfiguration: {
          autoResolved: true
          timeToResolve: 'PT5M'
        }
      }
      {
        alert: 'KubeNodeUnreachable'
        expression: 'max by (node) (kube_node_status_condition{condition="Ready", status="true"}) == 0'
        'for': 'PT15M'
        severity: 0
        enabled: true
        labels: {
          severity: 'critical'
          team: 'platform'
          runbook: 'docs/RUNBOOK.md#node-not-ready'
        }
        annotations: {
          summary: 'Node {{ $labels.node }} is not Ready'
          description: 'A node has been NotReady for 15 minutes. Workloads may be rescheduled; check node health and capacity.'
        }
        actions: [
          {
            actionGroupId: criticalActionGroup.id
          }
        ]
        resolveConfiguration: {
          autoResolved: true
          timeToResolve: 'PT5M'
        }
      }

      // ---- Warning alerts (email / business hours) -----------------------
      {
        alert: 'ServiceLatencyBreached'
        expression: 'service:latency:p95 > 1'
        'for': 'PT15M'
        severity: 2
        enabled: true
        labels: {
          severity: 'warning'
          team: 'platform'
          runbook: 'docs/RUNBOOK.md#latency'
        }
        annotations: {
          summary: 'p95 latency above 1s on {{ $labels.application }}'
          description: 'p95 is {{ $value }}s. Investigate slow queries and lock contention.'
        }
        actions: [
          {
            actionGroupId: warningActionGroup.id
          }
        ]
        resolveConfiguration: {
          autoResolved: true
          timeToResolve: 'PT5M'
        }
      }
      {
        alert: 'InventoryLockContention'
        expression: 'sum(rate(inventory_concurrent_update_total{application="inventory-service"}[5m])) > 0.5'
        'for': 'PT10M'
        severity: 2
        enabled: true
        labels: {
          severity: 'warning'
          team: 'inventory'
          runbook: 'docs/RUNBOOK.md#inventory'
        }
        annotations: {
          summary: 'High optimistic-lock contention on inventory'
          description: 'Stock rows are contended. Emitted by InventoryService#mutateWithRetry on every OptimisticLockingFailureException. Consider partitioning hot SKUs by warehouse or reducing the reservation TTL.'
        }
        actions: [
          {
            actionGroupId: warningActionGroup.id
          }
        ]
        resolveConfiguration: {
          autoResolved: true
          timeToResolve: 'PT5M'
        }
      }
      {
        alert: 'KafkaConsumerLagHigh'
        expression: 'sum by (application) (kafka_consumer_fetch_manager_records_lag) > 10000'
        'for': 'PT15M'
        severity: 2
        enabled: true
        labels: {
          severity: 'warning'
          team: 'platform'
          runbook: 'docs/RUNBOOK.md#consumer-lag'
        }
        annotations: {
          summary: 'Kafka consumer lag high on {{ $labels.application }}'
          description: 'Lag is {{ $value }}. Notifications and fulfilment are falling behind real time. Scale consumers or check for a poison message.'
        }
        actions: [
          {
            actionGroupId: warningActionGroup.id
          }
        ]
        resolveConfiguration: {
          autoResolved: true
          timeToResolve: 'PT5M'
        }
      }
      {
        alert: 'DeadLetterQueueFilling'
        expression: 'sum by (application) (rate(messaging_dead_letter_total[5m])) > 0.1'
        'for': 'PT10M'
        severity: 2
        enabled: true
        labels: {
          severity: 'warning'
          team: 'platform'
          runbook: 'docs/RUNBOOK.md#dead-letter'
        }
        annotations: {
          summary: 'Messages are being dead-lettered on {{ $labels.application }}'
          description: 'Poison messages detected; inspect the .dlq topic. Emitted by the common recoverer in MeshMessagingAutoConfiguration once a record has exhausted its retry budget.'
        }
        actions: [
          {
            actionGroupId: warningActionGroup.id
          }
        ]
        resolveConfiguration: {
          autoResolved: true
          timeToResolve: 'PT5M'
        }
      }
      {
        alert: 'PodCrashLooping'
        expression: 'sum by (namespace, pod) (increase(kube_pod_container_status_restarts_total{namespace="ecommerce"}[10m])) > 0.1'
        'for': 'PT5M'
        severity: 2
        enabled: true
        labels: {
          severity: 'warning'
          team: 'platform'
          runbook: 'docs/RUNBOOK.md#pod-crash-looping'
        }
        annotations: {
          summary: 'Container restarting in {{ $labels.namespace }}/{{ $labels.pod }}'
          description: 'A container is restarting repeatedly. Check the previous container logs and the liveness probe.'
        }
        actions: [
          {
            actionGroupId: warningActionGroup.id
          }
        ]
        resolveConfiguration: {
          autoResolved: true
          timeToResolve: 'PT5M'
        }
      }
      {
        alert: 'MemoryPressureHigh'
        expression: 'sum by (namespace, pod) (container_memory_working_set_bytes{namespace="ecommerce"}) / clamp_min(sum by (namespace, pod) (kube_pod_container_resource_limits{resource="memory", namespace="ecommerce"}), 1) > 0.9'
        'for': 'PT15M'
        severity: 2
        enabled: true
        labels: {
          severity: 'warning'
          team: 'platform'
          runbook: 'docs/RUNBOOK.md#memory-pressure'
        }
        annotations: {
          summary: 'Memory working set above 90% of limit on {{ $labels.pod }}'
          description: 'Working set is {{ $value | humanizePercentage }} of the container memory limit. Expect an OOM kill if this is sustained.'
        }
        actions: [
          {
            actionGroupId: warningActionGroup.id
          }
        ]
        resolveConfiguration: {
          autoResolved: true
          timeToResolve: 'PT5M'
        }
      }
      {
        alert: 'KubeContainerOOMKilled'
        expression: 'max by (namespace, pod, container) (kube_pod_container_status_last_terminated_reason{reason="OOMKilled", namespace="ecommerce"}) > 0'
        'for': 'PT5M'
        severity: 2
        enabled: true
        labels: {
          severity: 'warning'
          team: 'platform'
          runbook: 'docs/RUNBOOK.md#memory-pressure'
        }
        annotations: {
          summary: 'Container OOMKilled in {{ $labels.namespace }}/{{ $labels.pod }}'
          description: 'The last termination reason for this container was OOMKilled. Check the heap limit and the container memory limit before raising either.'
        }
        actions: [
          {
            actionGroupId: warningActionGroup.id
          }
        ]
      }
      {
        alert: 'KubeDeploymentReplicasMismatch'
        expression: 'kube_deployment_status_replicas_available{namespace="ecommerce"} != kube_deployment_spec_replicas{namespace="ecommerce"}'
        'for': 'PT10M'
        severity: 2
        enabled: true
        labels: {
          severity: 'warning'
          team: 'platform'
          runbook: 'docs/RUNBOOK.md#no-healthy-replicas'
        }
        annotations: {
          summary: 'Deployment {{ $labels.deployment }} has fewer available replicas than desired'
          description: 'Available replicas do not match the spec. Check scheduling, image pulls, and readiness probes.'
        }
        actions: [
          {
            actionGroupId: warningActionGroup.id
          }
        ]
      }
      {
        alert: 'KubeHpaMaxedOut'
        expression: 'kube_horizontalpodautoscaler_status_current_replicas{namespace="ecommerce"} == kube_horizontalpodautoscaler_spec_max_replicas{namespace="ecommerce"}'
        'for': 'PT15M'
        severity: 2
        enabled: true
        labels: {
          severity: 'warning'
          team: 'platform'
          runbook: 'docs/RUNBOOK.md#hpa-maxed-out'
        }
        annotations: {
          summary: 'HPA {{ $labels.horizontalpodautoscaler }} is at max replicas'
          description: 'The autoscaler has no headroom. Check whether the max is intentional and whether the node pool can absorb more pods.'
        }
        actions: [
          {
            actionGroupId: warningActionGroup.id
          }
        ]
      }
      {
        alert: 'BFFRefreshRejected'
        expression: 'sum(rate(mesh_business_events_total{domain="bff_session",outcome="refresh_rejected"}[5m])) > 0.1'
        'for': 'PT10M'
        severity: 2
        enabled: true
        labels: {
          severity: 'warning'
          team: 'bff'
          runbook: 'docs/BFF-SESSION.md'
        }
        annotations: {
          summary: 'BFF refresh tokens are being rejected'
          description: 'Sessions are failing to refresh. Check cookie domain, SameSite, token rotation, and clock skew before blaming the identity provider.'
        }
        actions: [
          {
            actionGroupId: warningActionGroup.id
          }
        ]
      }
      {
        alert: 'BFFNoRefreshToken'
        expression: 'sum(increase(mesh_business_events_total{domain="bff_session",outcome="no_refresh_token"}[10m])) > 0'
        'for': 'PT5M'
        severity: 2
        enabled: true
        labels: {
          severity: 'warning'
          team: 'bff'
          runbook: 'docs/BFF-SESSION.md'
        }
        annotations: {
          summary: 'BFF sessions are missing a refresh token'
          description: 'A refresh path is being reached without a refresh token. This is usually a cookie or proxy configuration change, not normal anonymous traffic.'
        }
        actions: [
          {
            actionGroupId: warningActionGroup.id
          }
        ]
      }
    ]
  }
}

output criticalActionGroupId string = criticalActionGroup.id
output warningActionGroupId string = warningActionGroup.id
output ruleGroupId string = ruleGroup.id
