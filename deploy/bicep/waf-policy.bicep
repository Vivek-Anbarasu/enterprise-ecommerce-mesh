// Azure WAF policy for the enterprise-ecommerce edge, plus the SecurityPolicy
// child resource that makes it referenceable by the ALB Controller.
//
// Why Bicep and not the Helm chart: `SecurityPolicy` is an ARM child resource of
// the Application Gateway for Containers instance. It is not a Kubernetes object,
// so no chart template can create it. The chart owns the other half - the
// `WebApplicationFirewallPolicy` custom resource - and references the policy this
// file produces by ARM ID.
//
// Deploy BOTH halves, or the WAF silently does nothing:
//   1. this file                      -> Azure: which policies the controller may reference
//   2. charts/.../web-application-firewall.yaml -> cluster: which policy applies, where
//
// The SecurityPolicy alone is not enough: AGC shows the policy as associated in
// the portal, but inspects no traffic and emits no firewall logs.
//
//   az deployment group create \
//     --resource-group rg-ecommerce-prod \
//     --template-file deploy/bicep/waf-policy.bicep \
//     --parameters agcResourceId=<id> wafPolicyName=ecommerce-edge-waf policyMode=Detection
//
// Start in Detection. A rate limit rule on a real customer base produces false
// positives long before anyone has watched it, and Detection logs what it would
// have blocked without breaking anyone.

targetScope = 'resourceGroup'

@description('Full ARM ID of the ApplicationLoadBalancer resource (Application Gateway for Containers) this policy attaches to.')
param agcResourceId string

@description('Name of the WAF policy. Must be in the same subscription AND region as the Application Gateway for Containers resource - cross-region policies are not supported.')
param wafPolicyName string = 'ecommerce-edge-waf'

@description('Detection logs what would be blocked. Switch to Prevention only after watching the logs.')
@allowed(['Detection', 'Prevention'])
param policyMode string = 'Detection'

@description('Per-client-IP request ceiling on sign-in endpoints, per duration. Credential-stuffing defence, so deliberately tight.')
param authRateLimitThreshold int = 20

@description('Window for the sign-in rate limit rule.')
@allowed(['OneMin', 'FiveMin', 'TenMin', 'Hour'])
param authRateLimitDuration string = 'OneMin'

@description('Per-client-IP request ceiling on anonymously browsable catalogue endpoints.')
param anonymousRateLimitThreshold int = 300

@description('Window for the anonymous catalogue rate limit rule.')
@allowed(['OneMin', 'FiveMin', 'TenMin', 'Hour'])
param anonymousRateLimitDuration string = 'OneMin'

@description('Sign-in and session-management paths. Tight limit: these are the endpoints worth guessing against.')
param authPaths array = [
  '/bff/oauth2/'
  '/bff/logout'
  '/login'
]

@description('Anonymously browsable read paths, including the BFF storefront proxy.')
param anonymousPaths array = [
  '/api/products'
  '/api/categories'
  '/api/reviews'
  '/bff/api/public'
]

resource wafPolicy 'Microsoft.Network/applicationGatewayWebApplicationFirewallPolicies@2024-05-01' = {
  name: wafPolicyName
  location: resourceGroup().location
  sku: {
    // WAF tier is required for rate limit rules; Standard has no WAF engine.
    name: 'WAF_v2'
  }
  properties: {
    policySettings: {
      // AGC supports only DRS 2.1 - no CRS managed rule sets.
      state: policyMode
      mode: policyMode
    }
    customRules: [
      {
        // Lower priority is evaluated first, so the tight sign-in limit wins
        // before the looser catalogue one can count those requests.
        name: 'perIp-auth-endpoints'
        priority: 10
        ruleType: 'RateLimitRule'
        rateLimitDuration: authRateLimitDuration
        rateLimitThreshold: authRateLimitThreshold
        // Grouping by the real client address as AGC observed it on the socket.
        //
        // `ClientAddrXFFHeader` would group by an X-Forwarded-For value instead,
        // which a caller controls - rotating the header per request would buy an
        // attacker a fresh counter each time and make this rule inert. It is
        // additionally unavailable here: AGC's WAF does not support the XFF
        // variable in custom rules at all.
        groupByUserSession: [
          {
            groupByVariables: [
              {
                variableName: 'ClientAddr'
              }
            ]
          }
        ]
        action: 'Block'
        matchConditions: [
          {
            // Scoping by path rather than by IP range.
            //
            // "Match every IP" is expressed in the Azure docs as an IPMatch
            // against 255.255.255.255/32 with negation on, which is a hack: get
            // the CIDR wrong and the rule blocks 100% of traffic with a 403 and no
            // rate limiting whatsoever. Matching UriPath instead needs no IP
            // range, so there is no equivalent IPv6 trap (0.0.0.0/0 alone matches
            // no IPv6 client, silently exempting every v6 address).
            matchVariable: 'UriPath'
            operator: 'BeginsWith'
            // Negation false: the rule applies only to these paths.
            negationCondition: false
            matchValues: authPaths
            transforms: []
          }
        ]
      }
      {
        name: 'perIp-anonymous-catalogue'
        priority: 20
        ruleType: 'RateLimitRule'
        rateLimitDuration: anonymousRateLimitDuration
        rateLimitThreshold: anonymousRateLimitThreshold
        groupByUserSession: [
          {
            groupByVariables: [
              {
                variableName: 'ClientAddr'
              }
            ]
          }
        ]
        action: 'Block'
        matchConditions: [
          {
            matchVariable: 'UriPath'
            operator: 'BeginsWith'
            negationCondition: false
            matchValues: anonymousPaths
            transforms: []
          }
        ]
      }
    ]
  }
}

// The child resource that scopes which WAF policies the ALB Controller may
// reference. Creating the policy above is not enough on its own.
resource securityPolicy 'Microsoft.Network/applicationGateways/securityPolicies@2024-05-01' = {
  name: 'waf-${wafPolicyName}'
  parent: agcResourceId
  properties: {
    // Association only. The Kubernetes-side WebApplicationFirewallPolicy is what
    // decides the enforcement scope.
    wafPolicy: {
      id: wafPolicy.id
    }
    priority: 100
  }
}

output wafPolicyResourceId string = wafPolicy.id
output securityPolicyName string = securityPolicy.name