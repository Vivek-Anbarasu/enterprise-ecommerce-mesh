package com.enterprise.mesh.shipping;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Shipment lifecycle and carrier integration.
 *
 * <p>Primarily event-driven, but exposes a small HTTP surface for carrier
 * webhooks and support tooling.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ShippingDeliveryApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShippingDeliveryApplication.class, args);
    }
}
