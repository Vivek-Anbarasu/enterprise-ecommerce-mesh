package com.enterprise.mesh.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Payment authorisation, capture and refund ledger.
 *
 * <p>PCI DSS scope is deliberately minimal: this service accepts only an opaque,
 * single-use token produced by the provider's client SDK. No PAN, CVV or expiry
 * ever transits or rests here.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
