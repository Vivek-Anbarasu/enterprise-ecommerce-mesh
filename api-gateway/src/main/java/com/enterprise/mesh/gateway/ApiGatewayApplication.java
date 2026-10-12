package com.enterprise.mesh.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Edge of the mesh. Terminates client TLS (via the ingress), validates the
 * bearer token, enforces per-route rate limits, and routes to downstream
 * services by Kubernetes Service DNS name. There is no service registry in the
 * gateway: discovery of instances is the cluster's responsibility (CoreDNS for
 * names, Istio Envoy for mTLS and per-connection routing).
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
