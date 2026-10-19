package com.enterprise.mesh.payment.service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.mesh.payment.domain.GatewayResult;
import com.enterprise.mesh.payment.domain.Payment;
import com.enterprise.mesh.payment.domain.PaymentGateway;
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
                    existing.get().getId(), existing.get().getStatus());
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

        // Provider first, local state second, and the order matters.
        //
        // If the local row were flipped to CAPTURED first and the provider call
        // then failed, this service would report revenue recognised for money it
        // never took - the exact silent failure this method previously had, where
        // capture only ever touched the database.
        //
        // Taking the money first inverts that failure: funds are taken but the row
        // is still AUTHORISED. The caller retries, the idempotency key below makes
        // the retry the same capture rather than a second debit, and the service
        // converges. An unrecoverable gap is recoverable; a phantom sale is not.
        gateway.capture(payment.getProviderReference(), payment.getAmount(),
                payment.getCurrency(), captureIdempotencyKey(payment));

        payment.capture();
        return paymentRepository.save(payment);
    }

    /**
     * Idempotency key for the capture call.
     *
     * <p>Derived from the authorisation's key rather than reusing it. Providers
     * typically scope idempotency keys per merchant across all endpoints, so
     * replaying the authorisation's own key on a different call would make the
     * provider recognise the capture as a repeat of the <em>authorisation</em> and
     * return its stored response instead of taking the money. The suffix keeps the
     * two distinct while staying deterministic, which is what makes a retry safe.
     *
     * <p>Uses the idempotency key rather than the payment id because the key is
     * already unique per tenant, whereas payment ids are not obviously scoped that
     * way for a provider holding merchant-wide idempotency state.
     */
    private String captureIdempotencyKey(Payment payment) {
        return payment.getIdempotencyKey() + ":capture";
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

    /** Thrown when a payment is not visible to the calling tenant. */
    public static class PaymentNotFoundException extends RuntimeException {
        public PaymentNotFoundException(String paymentId) {
            super("Payment " + paymentId + " not found");
        }
    }
}