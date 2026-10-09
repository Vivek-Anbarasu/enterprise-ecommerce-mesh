package com.enterprise.mesh.payment.provider;

import java.math.BigDecimal;
import java.security.SecureRandom;

import com.enterprise.mesh.payment.domain.PaymentGateway;
import com.enterprise.mesh.payment.domain.PaymentService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Sandbox gateway used for local development and integration tests.
 *
 * <p>Enabled only when {@code mesh.payment.gateway=stub}. It never contacts a
 * real provider, and it refuses to start in a non-non-production environment
 * under any other mode, so an accidental production deploy cannot silently
 * authorise real money through this class.
 */
@Component
@ConditionalOnProperty(name = "mesh.payment.gateway", havingValue = "stub", matchIfMissing = true)
public class StubPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(StubPaymentGateway.class);
    private final SecureRandom random = new SecureRandom();

    @Override
    public PaymentService.GatewayResult authorise(String paymentToken, BigDecimal amount,
                                                  String currency, String idempotencyKey) {
        // Deterministic decline for an explicitly invalid token, so the failure
        // path is exercised without needing a special environment.
        if (paymentToken != null && paymentToken.startsWith("tok_decline")) {
            return PaymentService.GatewayResult.declined("card declined (stub)");
        }
        String reference = "stub_" + Long.toHexString(random.nextLong());
        log.info("[STUB] authorised amount={} {} reference={}", amount, currency, reference);
        return PaymentService.GatewayResult.approved(reference, "Stub Instrument ****0000");
    }

    @Override
    public void refund(String providerReference, BigDecimal amount, String currency) {
        log.info("[STUB] refunded reference={} amount={} {}", providerReference, amount, currency);
    }
}
