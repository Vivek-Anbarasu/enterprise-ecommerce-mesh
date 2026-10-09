package com.enterprise.mesh.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Edge of the mesh. Terminates client TLS (via the ingress), validates the
 * bearer token, enforces per-route rate limits, and routes to downstream
 * services through the Istio service mesh using {@code lb://} logical names.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
