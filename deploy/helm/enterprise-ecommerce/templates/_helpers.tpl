{{/*
Common helpers for the enterprise-ecommerce chart.
*/}}

{{/* Fully qualified image reference. */}}
{{- define "ecommerce.image" -}}
{{- $registry := .root.Values.global.imageRegistry -}}
{{- $repo := .service.image.repository -}}
{{- $tag := default .root.Chart.AppVersion .service.image.tag -}}
{{- printf "%s/ecommerce/%s:%s" $registry $repo $tag -}}
{{- end -}}

{{/* Standard labels. */}}
{{- define "ecommerce.labels" -}}
app.kubernetes.io/name: {{ .serviceName }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/version: {{ .root.Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
app.kubernetes.io/part-of: enterprise-ecommerce-mesh
helm.sh/chart: {{ .root.Chart.Name }}-{{ .root.Chart.Version }}
{{- end -}}

{{/* Selector labels. Never include version/chart labels: they are immutable
     once a Deployment exists, and changing them orphans running pods. */}}
{{- define "ecommerce.selectorLabels" -}}
app.kubernetes.io/name: {{ .serviceName }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end -}}

{{/* Name of the Secret holding this service's database credentials. */}}
{{- define "ecommerce.dbSecretName" -}}
{{- printf "%s-%s-db" .root.Release.Name .serviceName -}}
{{- end -}}

{{/* Name of the Secret holding this service's OAuth2 client credentials. */}}
{{- define "ecommerce.oauthSecretName" -}}
{{- printf "%s-%s-oauth" .root.Release.Name .serviceName -}}
{{- end -}}

{{/*
ServiceAccount a workload runs as.

Defaults to the shared `-workload` account. A service that needs an identity of
its own - because an AuthorizationPolicy grants or denies it specifically -
sets `serviceAccount: true` and gets its own account instead. That granularity is
the point: naming a single principal in a mesh policy is only meaningful if exactly
one workload holds it.
*/}}
{{- define "ecommerce.serviceAccountName" -}}
{{- if .service.serviceAccount -}}
{{- printf "%s-%s" .root.Release.Name .serviceName -}}
{{- else -}}
{{- printf "%s-workload" .root.Release.Name -}}
{{- end -}}
{{- end -}}

{{/*
Env block for a service. Merges the standard mesh variables with any
service-specific extras in .service.extraEnv.
*/}}
{{- define "ecommerce.env" -}}
- name: SERVER_PORT
  value: {{ .service.port | quote }}
- name: MESH_ENV
  value: {{ .root.Values.global.meshEnv | quote }}
- name: LOG_FORMAT
  value: {{ .root.Values.global.logFormat | quote }}
- name: LOG_LEVEL_ROOT
  value: {{ .root.Values.global.logLevelRoot | quote }}
- name: LOG_LEVEL_APP
  value: {{ .root.Values.global.logLevelApp | quote }}
- name: TRACING_SAMPLE_RATE
  value: {{ .root.Values.global.tracingSampleRate | quote }}
- name: OTLP_ENDPOINT
  value: {{ .root.Values.global.otlpEndpoint | quote }}
- name: REDIS_HOST
  value: {{ .root.Values.infrastructure.redis.host | quote }}
- name: REDIS_PORT
  value: {{ .root.Values.infrastructure.redis.port | quote }}
- name: REDIS_SSL_ENABLED
  value: {{ .root.Values.infrastructure.redis.ssl | default false | quote }}
- name: KAFKA_BOOTSTRAP_SERVERS
  value: {{ .root.Values.infrastructure.kafka.bootstrapServers | quote }}
{{- /*
  Kafka transport. Local Docker Kafka is PLAINTEXT; Azure Event Hubs exposes the
  same API only over SASL_SSL on :9093. The JAAS string contains the connection
  string, so it is read from a Secret and never rendered from values.
*/}}
{{- $kafka := .root.Values.infrastructure.kafka }}
{{- if $kafka.securityProtocol }}
- name: KAFKA_SECURITY_PROTOCOL
  value: {{ $kafka.securityProtocol | quote }}
{{- end }}
{{- if $kafka.saslMechanism }}
- name: KAFKA_SASL_MECHANISM
  value: {{ $kafka.saslMechanism | quote }}
{{- end }}
{{- if $kafka.jaasSecretName }}
- name: KAFKA_SASL_JAAS_CONFIG
  valueFrom:
    secretKeyRef:
      name: {{ printf "%s-%s" .root.Release.Name $kafka.jaasSecretName | quote }}
      key: {{ $kafka.jaasSecretKey | default "jaas-config" | quote }}
{{- end }}
{{- if .root.Values.global.entraAudience }}
- name: ENTRA_AUDIENCE
  value: {{ .root.Values.global.entraAudience | quote }}
{{- end }}
{{- /*
  Tenant allow-list, as a comma-delimited value Spring binds to
  mesh.tenant.allowed. Emitted only when non-empty so that an empty list stays
  empty rather than arriving as [""], which would allow-list the empty string.
*/}}
{{- $tenantAllowed := .root.Values.global.mesh.tenantAllowed | default (list) }}
{{- if $tenantAllowed }}
- name: MESH_TENANT_ALLOWED
  value: {{ join "," $tenantAllowed | quote }}
{{- end }}
{{- if .root.Values.global.mesh.tenantAllowAll }}
- name: MESH_TENANT_ALLOW_ALL
  value: "true"
{{- end }}
{{- if .service.database }}
- name: DB_URL
  valueFrom:
    secretKeyRef:
      name: {{ include "ecommerce.dbSecretName" (dict "root" .root "serviceName" .serviceName) }}
      key: url
- name: DB_USERNAME
  valueFrom:
    secretKeyRef:
      name: {{ include "ecommerce.dbSecretName" (dict "root" .root "serviceName" .serviceName) }}
      key: username
- name: DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ include "ecommerce.dbSecretName" (dict "root" .root "serviceName" .serviceName) }}
      key: password
{{- end }}
{{- if .service.oauthClientSecret }}
- name: ENTRA_CLIENT_SECRET
  valueFrom:
    secretKeyRef:
      name: {{ include "ecommerce.oauthSecretName" (dict "root" .root "serviceName" .serviceName) }}
      key: client-secret
{{- end }}
{{- if .service.mongodb }}
- name: MONGODB_URI
  value: {{ .root.Values.infrastructure.mongodb.uri | quote }}
{{- end }}
{{- /*
  Profiles are a list so a service can add one without redefining the whole
  variable. Setting SPRING_PROFILES_ACTIVE again in extraEnv would emit a second
  entry with the same name, and which of the two the kubelet honours is not
  something to leave to chance.

  `entra` is what makes common-security build its JwtDecoder, so a service listed
  in that profile validates the token the gateway forwarded instead of rejecting
  it. MeshJwtValidationSafetyCheck fails the pod when `production` is active with
  no decoder, so a service that drops `entra` crash-loops naming the missing
  property rather than quietly serving 401s to every authenticated request.
*/}}
- name: SPRING_PROFILES_ACTIVE
  value: {{ join "," (default (list "production") .service.profiles) | quote }}
{{- range $key, $value := .service.extraEnv }}
- name: {{ $key }}
  value: {{ $value | quote }}
{{- end }}
{{- end -}}

{{/* Container port name: HTTP services expose 'http', workers only actuator. */}}
{{- define "ecommerce.probePort" -}}
{{- if .service.headless }}{{ .service.managementPort | default 8081 }}{{ else }}{{ .service.port }}{{ end -}}
{{- end -}}
