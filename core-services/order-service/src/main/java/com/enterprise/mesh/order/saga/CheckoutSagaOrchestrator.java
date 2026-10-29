package com.enterprise.mesh.order.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.enterprise.mesh.observability.BusinessMetrics;
import com.enterprise.mesh.order.api.OrderDtos.LineResponse;
import com.enterprise.mesh.order.api.OrderDtos.OrderResponse;
import com.enterprise.mesh.order.api.OrderDtos.PlaceOrderRequest;
import com.enterprise.mesh.order.domain.Order;
import com.enterprise.mesh.order.saga.SagaExceptions.PaymentDeclinedException;
import com.enterprise.mesh.order.saga.SagaExceptions.SagaAbandonedException;

/**
 * Checkout saga orchestrator.
 *
 * <p>Steps: reserve stock -> authorise payment -> confirm the hold -> capture the
 * payment -> commit the order and emit. Failures unwind according to how far the
 * saga got, using queued compensations rather than one best-effort call.
 *
 * <p><b>The transaction boundary is the point of this class.</b> It is
 * deliberately not {@code @Transactional}: remote calls happen here, between
 * short transactions owned by {@link SagaStateStore}. Previously one database
 * transaction spanned both HTTP round trips, which held a connection and the
 * order's row lock for the duration and made the connection pool the ceiling on
 * concurrent checkouts. The request shape is unchanged - it is still synchronous
 * and still returns PAID or FAILED - but the database is no longer in the call
 * path.
 *
 * <p><b>Durability.</b> The order is written as PENDING before the first remote
 * call, and each step advances a persisted {@link SagaStep}. If the process dies,
 * {@link StaleSagaSweeper} fails the checkout and queues the undo implied by that
 * step; {@link SagaCompensationWorker} retries it until it lands. So "compensate"
 * means "queue a durable intent and try now", not "call once and hope".
 *
 * <p><b>Known limits.</b> A timeout on authorise is genuinely ambiguous: the
 * provider may have committed the hold even though this process never saw the
 * reference. The order is failed and the stock released, and the orphaned hold is
 * left to the provider's authorisation window - the same exposure the previous
 * implementation had. Making that airtight needs a reconciliation job against the
 * provider, not a change of orchestration mode.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CheckoutSagaOrchestrator {

    /** Statuses after which a replay must not run the saga again. */
    private static final Set<Order.OrderStatus> TERMINAL = Set.of(
            Order.OrderStatus.PAID, Order.OrderStatus.SHIPPED, Order.OrderStatus.DELIVERED,
            Order.OrderStatus.CANCELLED, Order.OrderStatus.REFUNDED, Order.OrderStatus.FAILED);

    private final SagaStateStore stateStore;
    private final InventorySagaClient inventory;
    private final PaymentSagaClient payments;
    private final SagaCompensationRunner compensations;
    private final BusinessMetrics businessMetrics;
    private final SagaProperties properties;

    /**
     * Runs the checkout saga.
     *
     * <p>Idempotent on {@code idempotencyKey}: a retry after a timeout returns the
     * stored order. If the original request is still in flight, the retry returns
     * that order's current state instead of running the steps a second time.
     */
    public OrderResponse placeOrder(PlaceOrderRequest request, String tenantId, String correlationId) {
        SagaStateStore.Started started = start(request, tenantId, correlationId);
        String orderId = started.order().id();

        if (isTerminal(started.order().status())) {
            log.info("Idempotent replay for key {} returning order {}",
                    request.idempotencyKey(), orderId);
            return started.order();
        }

        if (!claim(orderId)) {
            log.info("Checkout for order {} is already in progress; returning its current state", orderId);
            return stateStore.current(orderId);
        }

        try {
            return run(request, started.order(), tenantId, correlationId);
        } catch (SagaAbandonedException | OptimisticLockingFailureException e) {
            // Either the recovery sweep failed this checkout while the request
            // was still running, or it took the row between two steps. Report the
            // stored outcome rather than fighting for the row.
            log.warn("Stopping checkout for order {}: {}", orderId, e.getMessage());
            return stateStore.current(orderId);
        }
    }

    private SagaStateStore.Started start(PlaceOrderRequest request, String tenantId,
                                         String correlationId) {
        try {
            return stateStore.findOrCreate(request, tenantId, correlationId);
        } catch (DataIntegrityViolationException race) {
            // Another request with the same idempotency key inserted first. The
            // unique index decided the winner; return its order.
            return stateStore.findExisting(request.idempotencyKey(), tenantId)
                    .orElseThrow(() -> race);
        }
    }

    private boolean claim(String orderId) {
        try {
            return stateStore.claim(orderId);
        } catch (OptimisticLockingFailureException lostRace) {
            // Two retries claimed at the same instant. The other one is driving.
            return false;
        }
    }

    private OrderResponse run(PlaceOrderRequest request, OrderResponse order,
                              String tenantId, String correlationId) {
        String orderId = order.id();
        String reservationId = "res-" + orderId;
        String requestId = correlationId == null ? orderId : correlationId;

        // ---- Step 1: reserve stock -------------------------------------
        try {
            inventory.reserve(reservationId, orderId, toStockLines(order.lines()),
                    Instant.now().plusSeconds(properties.getReservationTtlSeconds()),
                    tenantId, requestId);
        } catch (RuntimeException e) {
            log.warn("Stock reservation failed for order {}: {}", orderId, e.toString());
            businessMetrics.recordOutcome("orders", "stock_unavailable");
            // No compensation: the step never committed. If inventory committed
            // the hold but the answer was lost, the reservation sweeper reclaims
            // it at TTL.
            return failAndCompensate(orderId, "stock unavailable: " + describe(e), correlationId);
        }
        stateStore.markStockReserved(orderId, reservationId);

        // ---- Step 2: authorise payment ---------------------------------
        String paymentReference;
        try {
            paymentReference = payments.authorise(orderId, order.totalAmount(), order.currency(),
                    request.paymentToken(), tenantId);
        } catch (PaymentDeclinedException e) {
            log.info("Payment declined for order {}: {}", orderId, e.getMessage());
            businessMetrics.recordOutcome("orders", "payment_failed");
            return failAndCompensate(orderId, e.getMessage(), correlationId);
        } catch (RuntimeException e) {
            log.warn("Payment authorisation failed for order {}: {}", orderId, e.toString());
            businessMetrics.recordOutcome("orders", "payment_failed");
            return failAndCompensate(orderId, "payment unavailable: " + describe(e), correlationId);
        }
        stateStore.markPaymentAuthorised(orderId, paymentReference);

        // ---- Step 3: commit the stock hold ------------------------------
        try {
            inventory.confirm(reservationId, tenantId);
            stateStore.markStockConfirmed(orderId);
        } catch (RuntimeException e) {
            // Kept non-fatal on purpose: payment is already authorised, and
            // failing here would cancel a sale the customer has just approved.
            // The degradation is oversell - the hold expires at TTL and the units
            // return to sale while the order is paid - so this is an error, not a
            // warning. A retried confirm for PAID orders is the next step up.
            log.error("Failed to confirm reservation {} for order {}; the hold expires at TTL "
                    + "and the units return to sale", reservationId, orderId, e);
        }

        // ---- Step 4: capture --------------------------------------------
        try {
            payments.capture(paymentReference, tenantId);
            stateStore.markPaymentCaptured(orderId);
        } catch (RuntimeException e) {
            log.error("Capture failed for order {}; voiding the authorisation and returning stock",
                    orderId, e);
            businessMetrics.recordOutcome("orders", "commit_failed");
            return failAndCompensate(orderId, "payment capture failed: " + describe(e), correlationId);
        }

        // ---- Step 5: commit and emit ------------------------------------
        try {
            OrderResponse paid = stateStore.markPaidAndEmit(orderId, correlationId);
            businessMetrics.recordOutcome("orders", "paid");
            log.info("Checkout completed order={} total={} {}",
                    paid.id(), paid.totalAmount(), paid.currency());
            return paid;
        } catch (RuntimeException e) {
            // Capture succeeded, so the undo here is a refund, not a void. The
            // queued actions are derived from the persisted step, which says
            // PAYMENT_CAPTURED - the reason this mapping is not left to the
            // caller.
            log.error("Checkout commit failed for order {}; refunding and returning stock",
                    orderId, e);
            businessMetrics.recordOutcome("orders", "commit_failed");
            return failAndCompensate(orderId, "checkout commit failed: " + describe(e), correlationId);
        }
    }

    /**
     * Fails the order, queues its compensations and tries them immediately.
     *
     * <p>The immediate attempt keeps the common case synchronous: the shopper gets
     * an answer only after the stock hold has actually been dropped. A failure to
     * run it is not fatal - the rows are queued and the worker retries - so this
     * never turns a correct 422 into a 500.
     */
    private OrderResponse failAndCompensate(String orderId, String reason, String correlationId) {
        SagaStateStore.Failure failure = stateStore.fail(orderId, reason, correlationId);
        if (failure.compensationQueued()) {
            try {
                compensations.runForOrder(orderId);
            } catch (RuntimeException e) {
                log.warn("Immediate compensation for order {} did not complete; it stays queued",
                        orderId, e);
            }
        }
        return failure.order();
    }

    private static boolean isTerminal(String status) {
        try {
            return TERMINAL.contains(Order.OrderStatus.valueOf(status));
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }

    private static List<InventorySagaClient.StockLine> toStockLines(List<LineResponse> lines) {
        return lines.stream()
                .map(line -> new InventorySagaClient.StockLine(line.sku(), line.quantity()))
                .toList();
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
