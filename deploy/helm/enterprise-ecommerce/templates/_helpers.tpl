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
- name: KAFKA_BOOTSTRAP_SERVERS
  value: {{ .root.Values.infrastructure.kafka.bootstrapServers | quote }}
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
{{- if .service.mongodb }}
- name: MONGODB_URI
  value: {{ .root.Values.infrastructure.mongodb.uri | quote }}
{{- end }}
- name: SPRING_PROFILES_ACTIVE
  value: production
{{- range $key, $value := .service.extraEnv }}
- name: {{ $key }}
  value: {{ $value | quote }}
{{- end }}
{{- end -}}

{{/* Container port name: HTTP services expose 'http', workers only actuator. */}}
{{- define "ecommerce.probePort" -}}
{{- if .service.headless }}{{ .service.managementPort | default 8081 }}{{ else }}{{ .service.port }}{{ end -}}
{{- end -}}
