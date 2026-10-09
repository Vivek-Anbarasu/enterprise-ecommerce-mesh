package com.enterprise.mesh.shipping;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * Simulated carrier for local development and integration tests.
 *
 * <p>Never contacts a real carrier. Activated by default so the fulfilment flow
 * is testable end to end; a real deployment must supply a gateway bean backed by
 * the carrier SDK and exclude this one.
 */
@Component
public class StubCarrierGateway implements ShippingService.CarrierGateway {

    private static final Logger log = LoggerFactory.getLogger(StubCarrierGateway.class);
    private static final DateTimeFormatter ETA = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SecureRandom random = new SecureRandom();

    @Override
    public CarrierLabel purchaseLabel(String address, String serviceLevel, String orderReference) {
        String tracking = "STUB" + Math.abs(random.nextLong());
        String eta = LocalDate.now().plusDays("express".equalsIgnoreCase(serviceLevel) ? 1 : 4)
                .format(ETA);
        log.info("[STUB] purchased {} label tracking={} eta={} order={}",
                serviceLevel, tracking, eta, orderReference);
        return new CarrierLabel("stub-carrier", tracking,
                "http://localhost/stub-labels/" + tracking + ".pdf", eta);
    }

    @Override
    public void cancelLabel(String trackingNumber) {
        log.info("[STUB] cancelled label tracking={}", trackingNumber);
    }
}
