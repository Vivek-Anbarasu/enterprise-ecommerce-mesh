# Bicep templates

Azure-side resources that **cannot** be expressed as Kubernetes objects, and so
cannot be created by the Helm chart.

## `waf-policy.bicep`

The Web Application Firewall policy for the north-south edge, plus the
`SecurityPolicy` child resource that makes it referenceable by the ALB Controller.

### Why this is not in the chart

Application Gateway for Containers splits WAF across two providers, and only one of
them is Kubernetes:

| Half | Where | Provider |
|---|---|---|
| WAF policy + `SecurityPolicy` child resource | Azure | `Microsoft.Network/applicationGateways/securityPolicies` |
| `WebApplicationFirewallPolicy` custom resource | Cluster | `alb.networking.azure.io/v1`, rendered by `deploy/helm/enterprise-ecommerce/templates/web-application-firewall.yaml` |

`SecurityPolicy` is an ARM child resource of the AGC instance. There is no CRD for
it, so no chart template can create it.

Creating **only** this half is a silent failure: AGC reports the policy as
associated in the portal and in Defender for Cloud, but inspects no traffic and
emits no firewall logs. Nothing errors. See `docs/AKS-DEPLOYMENT.md` section 2.5.

### Deploy

```bash
az deployment group create \
  --resource-group rg-ecommerce-prod \
  --template-file deploy/bicep/waf-policy.bicep \
  --parameters agcResourceId=<agc-arm-id> policyMode=Detection
```

Then take the `wafPolicyResourceId` output and enable the cluster half:

```bash
helm upgrade --install ecommerce deploy/helm/enterprise-ecommerce \
  -n ecommerce --create-namespace \
  -f deploy/helm/values-prod.yaml \
  --set webApplicationFirewall.enabled=true \
  --set webApplicationFirewall.policyResourceId=<id>
```

### Start in Detection

`policyMode` defaults to `Detection`, and that default is deliberate. A rate limit
rule applied to a real customer base produces false positives long before anyone
has watched it, and `Prevention` turns those into an outage. Watch the WAF logs
for a full traffic cycle, then promote.

### Parameters

| Parameter | Default | Notes |
|---|---|---|
| `agcResourceId` | *required* | Full ARM ID of the AGC resource |
| `wafPolicyName` | `ecommerce-edge-waf` | Must be in the same subscription **and region** as the AGC resource |
| `policyMode` | `Detection` | `Detection` or `Prevention` |
| `authRateLimitThreshold` | `20` | Per client IP per window, on sign-in paths |
| `authRateLimitDuration` | `OneMin` | |
| `anonymousRateLimitThreshold` | `300` | Per client IP per window, on catalogue paths |
| `anonymousRateLimitDuration` | `OneMin` | |
| `authPaths` | `/bff/oauth2/`, `/bff/logout`, `/login` | |
| `anonymousPaths` | `/api/products`, `/api/categories`, `/api/reviews`, `/bff/api/public` | |

These thresholds live **here and nowhere else**. The Helm values carry only the
policy's ARM ID and the enforcement scope, so there is no second copy to drift.

### Sizing reminder

The threshold is **per AGC instance**, not per gateway. Two instances seeing 270 and
230 requests from one client against a 400/min threshold will let all 500 through.
Divide the real per-client budget by the instance count.

### Two design choices worth not undoing

**Grouping is by `ClientAddr`, not by `X-Forwarded-For`.** An XFF-based grouping is
rotated by the caller, so a rule grouped that way hands out a fresh counter per
request and enforces nothing. AGC's WAF does not expose the XFF variable in custom
rules in any case.

**Rules match `UriPath`, not an IP range.** "Match every IP" is expressed in the
Azure docs as an `IPMatch` against `255.255.255.255/32` with negation on, which is
a trap: get the CIDR wrong and the rule blocks 100% of traffic with no rate
limiting at all. Matching on path needs no IP range, which also removes the IPv6
equivalent trap where `0.0.0.0/0` alone silently exempts every v6 client.