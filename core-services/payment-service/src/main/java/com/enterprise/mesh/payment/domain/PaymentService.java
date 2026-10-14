package com.enterprise.mesh.payment.domain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.mesh.payment.repository.PaymentRepository;

/**
 * Authorises, captures and refunds payments.
 *
 * <p>Every method is idempotent on the caller's {@code idempotencyKey}. That is
 * the single most important property here: a network timeout must never be
 * allowed to become a second charge, so a repeat request replays the stored
 * outcome instead of calling the provider again.
 *
 * <p>The provider call is abstracted behind {@link PaymentGateway} so the real
 * integration can be swapped without touching this logic, and so tests never
 * need network access.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentGateway gateway;
    /**
     * Authorises funds for an order.
     *
     * @return the payment; on replay this is the original attempt
     */
    @Transactional
    public Payment authorise(String idempotencyKey, String tenantId, String orderId,
                             BigDecimal amount, String currency, String paymentToken) {

        Optional<Payment> existing =
                paymentRepository.findByIdempotencyKeyAndTenantId(idempotencyKey, tenantId);
        if (existing.isPresent()) {
            log.info("Idempotent replay for payment key {} returning {}",
                    idempotencyKey, existing.get().getStatus());
            return existing.get();
        }

        Payment payment = new Payment(UUID.randomUUID().toString(), idempotencyKey,
                tenantId, orderId, amount, currency);

        GatewayResult result = gateway.authorise(paymentToken, amount, currency, idempotencyKey);
        if (result.approved()) {
            payment.authorise(result.providerReference(), result.instrumentLabel());
            log.info("Authorised payment {} for order {} amount={} {}",
                    payment.getId(), orderId, amount, currency);
        } else {
            payment.decline(result.declineReason());
            log.info("Declined payment for order {}: {}", orderId, result.declineReason());
        }

        return paymentRepository.save(payment);
    }

    /** Captures previously authorised funds. Idempotent. */
    @Transactional
    public Payment capture(String paymentId, String tenantId) {
        Payment payment = loadOwned(paymentId, tenantId);
        if (payment.getStatus() == Payment.PaymentStatus.CAPTURED) {
            log.info("Payment {} already captured; no-op", paymentId);
            return payment;
        }
        payment.capture();
        return paymentRepository.save(payment);
    }

    /**
     * Releases an authorisation without taking money. Used as the saga
     * compensation when fulfilment fails after authorisation.
     */
    @Transactional
    public Payment voidAuthorisation(String paymentId, String tenantId) {
        Payment payment = loadOwned(paymentId, tenantId);
        if (payment.getStatus() == Payment.PaymentStatus.VOIDED) {
            return payment;
        }
        payment.voidAuthorisation();
        log.info("Voided authorisation for payment {}", paymentId);
        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment refund(String paymentId, String tenantId) {
        Payment payment = loadOwned(paymentId, tenantId);
        if (payment.getStatus() == Payment.PaymentStatus.REFUNDED) {
            return payment;
        }
        gateway.refund(payment.getProviderReference(), payment.getAmount(), payment.getCurrency());
        payment.refund();
        log.info("Refunded payment {} amount={} {}", paymentId, payment.getAmount(), payment.getCurrency());
        return paymentRepository.save(payment);
    }

    @Transactional(readOnly = true)
    public Payment get(String paymentId, String tenantId) {
        return loadOwned(paymentId, tenantId);
    }

    private Payment loadOwned(String paymentId, String tenantId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown payment " + paymentId));
        if (!payment.getTenantId().equals(tenantId)) {
            // Do not leak the existence of another tenant's payment.
            throw new PaymentNotFoundException(paymentId);
        }
        return payment;
    }

    /** Outcome of a provider call. */
    public record GatewayResult(boolean approved, String providerReference,
                                String instrumentLabel, String declineReason) {

        public static GatewayResult approved(String reference, String label) {
            return new GatewayResult(true, reference, label, null);
        }

        public static GatewayResult declined(String reason) {
            return new GatewayResult(false, null, null, reason);
        }
    }

    /** Thrown when a payment is not visible to the calling tenant. */
    public static class PaymentNotFoundException extends RuntimeException {
        public PaymentNotFoundException(String paymentId) {
            super("Payment " + paymentId + " not found");
        }
    }
}