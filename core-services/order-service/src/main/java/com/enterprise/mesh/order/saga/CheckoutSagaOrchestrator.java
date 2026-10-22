package com.enterprise.mesh.order.saga;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.MDC;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.enterprise.mesh.messaging.MessagingTopics;
import com.enterprise.mesh.messaging.event.EventMetadata;
import com.enterprise.mesh.observability.BusinessMetrics;
import com.enterprise.mesh.order.api.OrderDtos.LineRequest;
import com.enterprise.mesh.order.api.OrderDtos.OrderCreatedEvent;
import com.enterprise.mesh.order.api.OrderDtos.OrderResponse;
import com.enterprise.mesh.order.api.OrderDtos.OrderStatusEvent;
import com.enterprise.mesh.order.api.OrderDtos.PlaceOrderRequest;
import com.enterprise.mesh.order.domain.Order;
import com.enterprise.mesh.order.persistence.OrderRepository;

/**
 * Checkout saga orchestrator.
 *
 * <p>Steps: reserve stock -> authorise payment -> confirm the hold -> mark paid
 * and emit. Both failure points have a compensating action: a declined payment
 * releases the stock hold, and a failure after authorisation voids the
 * authorisation. This is the classic saga pattern; the alternative - a single
 * distributed transaction across three databases - is not available here and
 * would be worse even if it were.
 *
 * <p>Confirming the hold is what turns reserved units into a sold decrement.
 * Skipping it leaves the reservation PENDING, and the sweeper then returns the
 * units to sale at TTL - i.e. stock for a paid order gets resold.
 *
 * <p>All state changes and their events are written in one transaction via
 * {@link OutboxService}, so a crash can never leave an order whose event was
 * never published.
 */
@Service
@Slf4j
public class CheckoutSagaOrchestrator {

    private final OrderRepository orderRepository;
    private final OutboxService outboxService;
    private final RestClient restClient;
    private final BusinessMetrics businessMetrics;
    private final Duration reservationTtl;

    public CheckoutSagaOrchestrator(OrderRepository orderRepository,
                                    OutboxService outboxService,
                                    RestClient.Builder restClientBuilder,
                                    BusinessMetrics businessMetrics,
                                    @Value("${mesh.saga.reservation-ttl-seconds:900}") long reservationTtlSeconds) {
        this.orderRepository = orderRepository;
        this.outboxService = outboxService;
        this.restClient = restClientBuilder.build();
        this.businessMetrics = businessMetrics;
        this.reservationTtl = Duration.ofSeconds(reservationTtlSeconds);
    }

    /**
     * Runs the checkout saga.
     *
     * <p>Idempotent on {@code idempotencyKey}: a retried request returns the
     * existing order rather than creating a duplicate.
     */
    @Transactional
    public OrderResponse placeOrder(PlaceOrderRequest request, String tenantId, String correlationId) {
        var existing = orderRepository.findByIdempotencyKeyAndTenantId(
                request.idempotencyKey(), tenantId);
        if (existing.isPresent()) {
            log.info("Idempotent replay for key {} returning order {}",
                    request.idempotencyKey(), existing.get().getId());
            return OrderResponse.from(existing.get());
        }

        String orderId = UUID.randomUUID().toString();
        Order order = new Order(orderId, request.idempotencyKey(), tenantId,
                request.customerId(), request.shippingAddress(), correlationId);

        BigDecimal subtotal = BigDecimal.ZERO;
        for (LineRequest line : request.lines()) {
            // Use setScale(4, UNNECESSARY-safe HALF_UP) so money arithmetic
            // matches the invoice the customer sees.
            BigDecimal unitPrice = line.unitPrice().setScale(4, java.math.RoundingMode.HALF_UP);
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(line.quantity()))
                    .setScale(4, java.math.RoundingMode.HALF_UP);
            order.addLine(line.sku(), line.productName(), line.quantity(), unitPrice, lineTotal);
            subtotal = subtotal.add(lineTotal);
        }
        order.recalculateTotals(request.taxAmount(), request.shippingAmount());

        // ---- Step 1: reserve stock -------------------------------------
        String reservationId = "res-" + orderId;
        try {
            reserveStock(reservationId, order, tenantId);
            order.attachInventoryReservation(reservationId);
            order.transitionTo(Order.OrderStatus.RESERVED);
        } catch (RuntimeException e) {
            log.warn("Stock reservation failed for order {}: {}", orderId, e.toString());
            order.markFailed("stock unavailable: " + e.getMessage());
            outboxService.recordOrderEvent(MessagingTopics.ORDER_EVENTS,
                    new OrderStatusEvent(orderId, order.getCustomerId(), "PENDING",
                            order.getStatus().name(), "stock reservation failed", Instant.now()),
                    order, "order.failed", correlationId);
            OrderResponse response = OrderResponse.from(orderRepository.save(order));
            businessMetrics.recordOutcome("orders", "stock_unavailable");
            return response;
        }

        // ---- Step 2: authorise payment ---------------------------------
        try {
            String paymentReference = authorisePayment(order, request.paymentToken(), tenantId);
            order.attachPaymentReference(paymentReference);
            order.transitionTo(Order.OrderStatus.PAID);
        } catch (RuntimeException e) {
            log.warn("Payment authorisation failed for order {}: {}", orderId, e.toString());
            compensateReservation(order, tenantId);
            order.markFailed("payment declined: " + e.getMessage());
            outboxService.recordOrderEvent(MessagingTopics.ORDER_EVENTS,
                    new OrderStatusEvent(orderId, order.getCustomerId(), "RESERVED",
                            order.getStatus().name(), "payment declined", Instant.now()),
                    order, "order.failed", correlationId);
            OrderResponse response = OrderResponse.from(orderRepository.save(order));
            businessMetrics.recordOutcome("orders", "payment_failed");
            return response;
        }

        // ---- Step 3: commit the stock hold -------------------------------
        try {
            confirmReservation(order, tenantId);
        } catch (RuntimeException e) {
            // The order is about to roll back and the customer sees an error.
            // Count it as a failed checkout so the conversion ratio reflects it;
            // a 5xx-only view would miss a saga that failed after authorisation.
            businessMetrics.recordOutcome("orders", "confirm_failed");
            throw e;
        }

        // ---- Step 4: persist and emit -------------------------------------
        Order saved;
        try {
            saved = orderRepository.save(order);
            // Force the INSERT/UPDATE and any constraint check to happen here
            // rather than at transaction commit. Without this a constraint
            // violation surfaces after this method returns, the catch below never
            // runs, and the authorisation is left orphaned.
            orderRepository.flush();

            List<String> skus = request.lines().stream().map(LineRequest::sku).toList();
            outboxService.recordOrderEvent(MessagingTopics.ORDER_EVENTS,
                    new OrderCreatedEvent(saved.getId(), saved.getCustomerId(), reservationId,
                            saved.getTotalAmount(), saved.getCurrency(), skus, Instant.now()),
                    saved, "order.paid", correlationId);

            // Capture LAST in this try, and that position is load-bearing.
            //
            // payment-service commits its own transaction, so this call is not
            // undone by the rollback below. If capture ran before the outbox write
            // and that write then failed, the catch would try to void a payment that
            // is already CAPTURED - which Payment.voidAuthorisation rejects with
            // "Cannot void a payment in state CAPTURED". That exception would escape
            // the catch, replacing the real cause and skipping the stock
            // compensation and the failure event.
            //
            // Running last means every failure that can still be compensated leaves
            // the payment in AUTHORISED, where void is legal. A capture that fails
            // itself also leaves it AUTHORISED, because the gateway is called before
            // the local status flips.
            capturePayment(order, tenantId);
        } catch (RuntimeException e) {
            // Payment is authorised but this transaction is rolling back, so no
            // order will ever reference the authorisation. Void it.
            log.error("Checkout commit failed for order {}; voiding authorisation {}",
                    orderId, order.getPaymentReference(), e);
            voidPayment(order, tenantId);

            // Stock was already confirmed in Step 3, and release is guarded on the
            // reservation being PENDING - so a release here would be a silent no-op
            // and the decrement would stand for an order that will never exist.
            // Unconfirm is the only operation that puts on-hand stock back.
            compensateConfirmedReservation(order, tenantId, e);

            // Announced through the outbox rather than logged, because nothing
            // downstream can see this failure otherwise: the order row rolled back,
            // so fulfilment and notification never learn the order existed. The
            // event must therefore be written in its own transaction or it would
            // roll back with everything else.
            businessMetrics.recordOutcome("orders", "commit_failed");
            publishCommitFailure(order, tenantId, correlationId, e);
            throw e;
        }

        log.info("Checkout completed order={} total={} {} customer={}",
                saved.getId(), saved.getTotalAmount(), saved.getCurrency(), saved.getCustomerId());
        recordOutcomeAfterCommit("paid");
        return OrderResponse.from(saved);
    }

    /**
     * Records the successful checkout only after the transaction commits.
     *
     * <p>The counter is in-memory and is not rolled back with the database
     * transaction. Recording `paid` before the commit would count an order that
     * a constraint violation or a failed commit then discarded, which is exactly
     * the kind of false "healthy" signal the conversion alert must not produce.
     */
    private void recordOutcomeAfterCommit(String outcome) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    businessMetrics.recordOutcome("orders", outcome);
                }
            });
        } else {
            businessMetrics.recordOutcome("orders", outcome);
        }
    }

    /** Reserves the stock hold for the order's lines. */
    private void reserveStock(String reservationId, Order order, String tenantId) {
        List<StockLine> lines = order.getLines().stream()
                .map(line -> new StockLine(line.getSku(), line.getQuantity()))
                .toList();

        restClient.post()
                .uri("${mesh.clients.inventory}/api/inventory/reservations")
                .header("X-Tenant-ID", tenantId)
                .header("X-Request-ID", order.getCorrelationId())
                .header("Idempotency-Key", reservationId)
                .body(new ReserveRequest(reservationId, order.getId(), lines, Instant.now().plus(reservationTtl)))
                .retrieve()
                .onStatus(status -> status.value() == 409, (request, response) -> {
                    throw new InsufficientStockException("Stock reservation rejected for order " + order.getId());
                })
                .body(ReserveResponse.class);
    }

    /**
     * Releases the stock hold. Failure here is logged but not rethrown: the
     * inventory sweeper will expire the hold on its own, and failing here would
     * leave the customer with an error instead of a (correctly) cancelled order.
     */
    private void compensateReservation(Order order, String tenantId) {
        try {
            restClient.post()
                    .uri("${mesh.clients.inventory}/api/inventory/reservations/{id}/release",
                            order.getInventoryReservationId())
                    .header("X-Tenant-ID", tenantId)
                    .retrieve()
                    .body(Object.class);
            log.info("Released stock reservation for order {}", order.getId());
        } catch (RestClientException e) {
            log.error("Failed to release reservation for order {}; the inventory sweeper "
                    + "will reclaim it at TTL: {}", order.getId(), e.toString());
        }
    }

    /**
     * Takes the funds the shopper authorised.
     *
     * <p>Authorisation only places a hold, so without this the order completes,
     * stock is decremented and an {@code order.paid} event is emitted while the
     * customer has not actually been charged. The hold would then sit until the
     * provider's authorisation window expired.
     *
     * <p>Idempotent at both ends, so a retry after a timeout cannot double-charge:
     * payment-service replays the capture for an already-captured payment, and
     * forwards a derived key so the provider recognises a repeated call as the
     * same capture rather than a second debit.
     *
     * <p>Failure propagates. This is the one step after Step 3 that must not be
     * swallowed - a silent no-op here is precisely how an unpaid order ships.
     * The catch above then voids the authorisation and returns the stock.
     */
    private void capturePayment(Order order, String tenantId) {
        restClient.post()
                .uri("${mesh.clients.payment}/api/payments/{id}/capture",
                        order.getPaymentReference())
                .header("X-Tenant-ID", tenantId)
                .retrieve()
                .body(Object.class);
        log.info("Captured payment {} for order {}", order.getPaymentReference(), order.getId());
    }

    /**
     * Undoes the Step 3 confirm for an order that is about to be rolled back.
     *
     * <p>Best effort, on the same reasoning as {@link #compensateReservation}: the
     * shopper has already been failed, so throwing here would replace a correct
     * cancellation with a 500. But unlike a release the failure is not benign - if
     * this does not land, stock stays permanently decremented for an order that
     * does not exist, and only a manual goods receipt can put it back. So it is
     * logged at error with the recovery endpoint, not swallowed.
     */
    private void compensateConfirmedReservation(Order order, String tenantId, RuntimeException cause) {
        try {
            restClient.post()
                    .uri("${mesh.clients.inventory}/api/inventory/reservations/{id}/unconfirm",
                            order.getInventoryReservationId())
                    .header("X-Tenant-ID", tenantId)
                    .retrieve()
                    .body(Object.class);
            log.warn("Unconfirmed stock reservation for order {}; the order was not persisted",
                    order.getId());
        } catch (RestClientException unconfirmFailure) {
            log.error("Failed to unconfirm reservation for order {}. Stock was decremented in "
                            + "Step 3 and has NOT been returned, and no order references it. "
                            + "Recover with POST /api/inventory/<sku>/receipts. cause={}",
                    order.getId(), unconfirmFailure.toString(), cause);
        }
    }

    /**
     * Emits {@code order.failed} for a checkout that reached payment but never
     * persisted.
     *
     * <p>Recorded through {@link OutboxService#recordAfterRollback} rather than
     * {@code recordOrderEvent}, because this runs inside the catch of a
     * transaction that is already rolling back: an event written through the
     * normal outbox path would be rolled back with it and the failure would stay
     * invisible, which is what it looked like before this existed.
     */
    private void publishCommitFailure(Order order, String tenantId, String correlationId,
                                      RuntimeException cause) {
        try {
            outboxService.recordAfterRollback(MessagingTopics.ORDER_EVENTS,
                    new OrderStatusEvent(order.getId(), order.getCustomerId(),
                            order.getStatus().name(), order.getStatus().name(),
                            "checkout failed after payment authorisation: " + cause.getMessage(),
                            Instant.now()),
                    EventMetadata.of("order.failed", "order", order.getId(), tenantId, correlationId));
        } catch (RuntimeException publishFailure) {
            // The transaction is already failing; losing the notification on top of
            // that would be silent. The error log above still names the order.
            log.error("Could not publish order.failed for order {}; nothing downstream will "
                    + "learn this checkout failed", order.getId(), publishFailure);
        }
    }

    /**
     * Releases an authorisation without taking money.
     *
     * <p>Wired to the one point in this saga where a failure can occur after
     * payment has been authorised: the commit that records the order and its
     * outbox event. Everything before that is either a decline (compensated by
     * releasing stock) or a success.
     *
     * <p>This works across the service boundary because payment lives in its own
     * database. Our transaction rolls back; the void is committed independently by
     * payment-service. That independence is the entire reason a compensating
     * action is possible at all here.
     *
     * <p>Idempotent: voiding an already-voided authorisation is a no-op there.
     */
    private void voidPayment(Order order, String tenantId) {
        String reference = order.getPaymentReference();
        if (reference == null || reference.isBlank()) {
            return;
        }
        try {
            restClient.post()
                    .uri("${mesh.clients.payment}/api/payments/{id}/void", reference)
                    .header("X-Tenant-ID", tenantId)
                    .retrieve()
                    .body(Object.class);
            log.info("Voided authorisation {} for order {}", reference, order.getId());
        } catch (RestClientException e) {
            // Nothing retries this. The authorisation stays AUTHORISED and the
            // provider's own expiry is what eventually releases the hold, so this
            // is worth alerting on rather than swallowing quietly.
            log.error("Failed to void authorisation {} for order {}; it stays AUTHORISED "
                    + "until the provider expires the hold", reference, order.getId(), e);
        }
    }

    /**
     * Converts the stock hold into a sold decrement.
     *
     * <p>Without this the reservation stays PENDING and
     * {@code ReservationExpirySweeper} releases the units at TTL - so the stock
     * for a paid order goes back on sale roughly
     * {@code mesh.saga.reservation-ttl-seconds} after the customer paid for it.
     *
     * <p>Failure is logged, not thrown. Payment is already authorised by this
     * point, so failing the checkout would leave the customer with an error and an
     * authorisation nothing releases. A confirm that fails degrades to the old
     * behaviour - overselling rather than losing money.
     */
    private void confirmReservation(Order order, String tenantId) {
        try {
            restClient.post()
                    .uri("${mesh.clients.inventory}/api/inventory/reservations/{id}/confirm",
                            order.getInventoryReservationId())
                    .header("X-Tenant-ID", tenantId)
                    .retrieve()
                    .body(Object.class);
            log.info("Confirmed stock reservation for order {}", order.getId());
        } catch (RestClientException e) {
            log.error("Failed to confirm reservation for order {}; the hold expires at TTL "
                    + "and the units return to sale", order.getId(), e);
        }
    }

    private String authorisePayment(Order order, String paymentToken, String tenantId) {
        PaymentAuthorisation authorisation = restClient.post()
                .uri("${mesh.clients.payment}/api/payments/authorisations")
                .header("X-Tenant-ID", tenantId)
                .header("Idempotency-Key", order.getId())
                .body(new AuthorisationRequest(order.getId(), order.getTotalAmount(),
                        order.getCurrency(), paymentToken))
                .retrieve()
                .body(PaymentAuthorisation.class);

        if (authorisation == null || !authorisation.approved()) {
            throw new PaymentDeclinedException(
                    authorisation == null ? "empty provider response" : authorisation.declineReason());
        }
        return authorisation.reference();
    }

    // --------------------------------------------------------------- payloads

    private record StockLine(String sku, int quantity) {
    }

    private record ReserveRequest(String reservationId, String orderId,
                                  List<StockLine> lines, Instant expiresAt) {
    }

    private record ReserveResponse(boolean success) {
    }

    private record AuthorisationRequest(String orderId, BigDecimal amount,
                                       String currency, String paymentToken) {
    }

    private record PaymentAuthorisation(String reference, boolean approved, String declineReason) {
    }

    /** Raised when the payment provider declines; triggers saga compensation. */
    public static class PaymentDeclinedException extends RuntimeException {
        public PaymentDeclinedException(String reason) {
            super("Payment declined: " + reason);
        }
    }

    /** Raised on a 409 from inventory; surfaces as "sold out" to the shopper. */
    public static class InsufficientStockException extends RuntimeException {
        public InsufficientStockException(String message) {
            super(message);
        }
    }
}