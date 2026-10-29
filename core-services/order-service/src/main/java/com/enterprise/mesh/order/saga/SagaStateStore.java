package com.enterprise.mesh.order.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.mesh.messaging.MessagingTopics;
import com.enterprise.mesh.order.api.OrderDtos.LineRequest;
import com.enterprise.mesh.order.api.OrderDtos.OrderCreatedEvent;
import com.enterprise.mesh.order.api.OrderDtos.OrderResponse;
import com.enterprise.mesh.order.api.OrderDtos.OrderStatusEvent;
import com.enterprise.mesh.order.api.OrderDtos.PlaceOrderRequest;
import com.enterprise.mesh.order.domain.Order;
import com.enterprise.mesh.order.domain.OrderLine;
import com.enterprise.mesh.order.persistence.OrderRepository;
import com.enterprise.mesh.order.saga.SagaExceptions.SagaAbandonedException;

/**
 * The saga's short transactions.
 *
 * <p>Every method here is one unit of database work and nothing else: no HTTP,
 * no waiting. That is the whole point of the class. The orchestrator used to run
 * the remote calls inside a single {@code @Transactional} method, which held a
 * Hikari connection and the order's row lock across two or three network round
 * trips and capped concurrent checkouts at the pool size.
 *
 * <p>Order of operations per step: call the participant first, then record the
 * outcome here. A crash between the two therefore leaves the step un-advanced,
 * and the next attempt replays an idempotent call rather than skipping work.
 *
 * <p>The order is persisted as {@code PENDING} before the first remote call, so
 * an in-flight checkout is visible and compensatable after a restart. Its status
 * only becomes {@code PAID} at the final commit; until then it stays
 * {@code RESERVED}, which is what keeps a half-finished checkout from looking
 * successful in the orders API.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SagaStateStore {

    private static final int MAX_REASON_LENGTH = 512;

    private final OrderRepository orderRepository;
    private final SagaStateRepository sagaStateRepository;
    private final OutboxService outboxService;
    private final SagaCompensationStore compensations;
    private final SagaProperties properties;

    /**
     * Creates the durable PENDING order, or returns the one an earlier request
     * already created.
     *
     * <p>Two concurrent requests with the same idempotency key can both miss the
     * lookup; the unique index lets exactly one insert win. The loser gets a
     * {@link org.springframework.dao.DataIntegrityViolationException} and the
     * orchestrator re-reads. That is why the insert is flushed here instead of
     * being left to commit time.
     */
    @Transactional
    public Started findOrCreate(PlaceOrderRequest request, String tenantId, String correlationId) {
        Optional<Order> existing =
                orderRepository.findByIdempotencyKeyAndTenantId(request.idempotencyKey(), tenantId);
        if (existing.isPresent()) {
            return describe(existing.get());
        }

        String orderId = UUID.randomUUID().toString();
        Order order = new Order(orderId, request.idempotencyKey(), tenantId,
                request.customerId(), request.shippingAddress(), correlationId);

        for (LineRequest line : request.lines()) {
            // Four decimal places to match NUMERIC(19,4), so the stored snapshot
            // is exactly the invoice the customer saw.
            BigDecimal unitPrice = line.unitPrice().setScale(4, RoundingMode.HALF_UP);
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(line.quantity()))
                    .setScale(4, RoundingMode.HALF_UP);
            order.addLine(line.sku(), line.productName(), line.quantity(), unitPrice, lineTotal);
        }
        order.recalculateTotals(request.taxAmount(), request.shippingAmount());

        orderRepository.save(order);
        orderRepository.flush();

        sagaStateRepository.save(new SagaState(orderId));
        log.info("Started checkout saga for order {} (tenant {})", orderId, tenantId);
        return new Started(OrderResponse.from(order), SagaStep.STARTED, false);
    }

    @Transactional(readOnly = true)
    public Optional<Started> findExisting(String idempotencyKey, String tenantId) {
        return orderRepository.findByIdempotencyKeyAndTenantId(idempotencyKey, tenantId)
                .map(this::describe);
    }

    /**
     * Takes the lease for this saga.
     *
     * <p>False means one of: already completed, or another request holds a live
     * claim. The caller reports the stored state instead of running the steps
     * twice.
     */
    @Transactional
    public boolean claim(String orderId) {
        SagaState state = sagaStateRepository.findById(orderId).orElse(null);
        if (state == null || state.getStep().isTerminal()) {
            return false;
        }
        Instant now = Instant.now();
        if (state.hasLiveClaim(now, properties.getClaimLease())) {
            return false;
        }
        state.claim(UUID.randomUUID().toString(), now);
        return true;
    }

    @Transactional(readOnly = true)
    public OrderResponse current(String orderId) {
        return orderRepository.findById(orderId)
                .map(OrderResponse::from)
                .orElseThrow(() -> new SagaAbandonedException("Order " + orderId + " no longer exists"));
    }

    /** Records the stock hold. Idempotent when the step is already reached. */
    @Transactional
    public void markStockReserved(String orderId, String reservationId) {
        SagaState state = requireState(orderId);
        if (state.getStep().hasReached(SagaStep.STOCK_RESERVED)) {
            return;
        }
        Order order = requireInFlight(orderId);
        order.attachInventoryReservation(reservationId);
        order.transitionTo(Order.OrderStatus.RESERVED);
        state.advanceTo(SagaStep.STOCK_RESERVED);
    }

    /**
     * Records the authorisation reference.
     *
     * <p>Deliberately does not move the order to PAID: an authorisation is not a
     * sale. A checkout that crashes after this point must still be compensatable,
     * and a PAID row would both claim success and make FAILED unreachable.
     */
    @Transactional
    public void markPaymentAuthorised(String orderId, String paymentReference) {
        SagaState state = requireState(orderId);
        if (state.getStep().hasReached(SagaStep.PAYMENT_AUTHORISED)) {
            return;
        }
        Order order = requireInFlight(orderId);
        order.attachPaymentReference(paymentReference);
        state.advanceTo(SagaStep.PAYMENT_AUTHORISED);
    }

    @Transactional
    public void markStockConfirmed(String orderId) {
        SagaState state = requireState(orderId);
        if (state.getStep().hasReached(SagaStep.STOCK_CONFIRMED)) {
            return;
        }
        requireInFlight(orderId);
        state.advanceTo(SagaStep.STOCK_CONFIRMED);
    }

    @Transactional
    public void markPaymentCaptured(String orderId) {
        SagaState state = requireState(orderId);
        if (state.getStep().hasReached(SagaStep.PAYMENT_CAPTURED)) {
            return;
        }
        requireInFlight(orderId);
        state.advanceTo(SagaStep.PAYMENT_CAPTURED);
    }

    /**
     * Final commit: order PAID, {@code order.paid} in the outbox, saga completed.
     *
     * <p>One transaction, so the order can never be visible as paid without its
     * event being queued. The flush surfaces a constraint violation here rather
     * than at commit, where the orchestrator could no longer tell which step
     * failed.
     */
    @Transactional
    public OrderResponse markPaidAndEmit(String orderId, String correlationId) {
        SagaState state = requireState(orderId);
        if (!state.getStep().hasReached(SagaStep.PAYMENT_CAPTURED)) {
            throw new IllegalStateException(
                    "Refusing to mark order " + orderId + " paid at step " + state.getStep());
        }
        Order order = requireInFlight(orderId);
        order.transitionTo(Order.OrderStatus.PAID);

        List<String> skus = order.getLines().stream().map(OrderLine::getSku).toList();
        outboxService.recordOrderEvent(MessagingTopics.ORDER_EVENTS,
                new OrderCreatedEvent(order.getId(), order.getCustomerId(),
                        order.getInventoryReservationId(), order.getTotalAmount(),
                        order.getCurrency(), skus, Instant.now()),
                order, "order.paid", correlationId);

        state.advanceTo(SagaStep.COMPLETED);
        state.releaseClaim();
        orderRepository.flush();

        return OrderResponse.from(order);
    }

    /**
     * Fails the order and queues whatever undoing its progress requires.
     *
     * <p>The set of compensations is derived from the recorded step, not from the
     * caller's guess about where the failure happened. That keeps the live path
     * and the restart-recovery sweep honest about the same committed facts.
     *
     * <p>Returns whether anything was queued so the orchestrator can skip the
     * immediate-compensation pass when there is nothing to undo - the common case
     * for a sold-out SKU.
     */
    @Transactional
    public Failure fail(String orderId, String reason, String correlationId) {
        SagaState state = requireState(orderId);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new SagaAbandonedException("Order " + orderId + " no longer exists"));
        return failInternal(order, state, reason, correlationId);
    }

    /**
     * Recovery entry point for a saga whose orchestrator died.
     *
     * <p>Returns false when the saga is no longer a candidate: completed, claim
     * refreshed by a live request, or the order already terminal. The re-check
     * happens in this transaction, so a slow sweep cannot fail a checkout that
     * finished between the scan and the update.
     */
    @Transactional
    public boolean abandon(String orderId, Instant staleBefore) {
        SagaState state = sagaStateRepository.findById(orderId).orElse(null);
        if (state == null || state.getStep().isTerminal() || !state.claimIsStale(staleBefore)) {
            return false;
        }
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null || !isInFlight(order.getStatus())) {
            state.releaseClaim();
            return false;
        }
        log.warn("Failing abandoned checkout for order {} at step {}", orderId, state.getStep());
        failInternal(order, state, "checkout abandoned: the orchestrator did not finish", null);
        return true;
    }

    private Failure failInternal(Order order, SagaState state, String reason, String correlationId) {
        if (order.getStatus() == Order.OrderStatus.FAILED) {
            state.releaseClaim();
            return new Failure(OrderResponse.from(order), false);
        }
        if (!isInFlight(order.getStatus())) {
            throw new SagaAbandonedException(
                    "Order " + order.getId() + " is " + order.getStatus() + ", not in flight");
        }

        Order.OrderStatus previous = order.getStatus();
        order.markFailed(reason);

        Instant now = Instant.now();
        boolean queued = false;
        for (CompensationAction action : CompensationAction.forStep(state.getStep())) {
            String target = action.usesReservation()
                    ? order.getInventoryReservationId()
                    : order.getPaymentReference();
            if (target == null || target.isBlank()) {
                // No reference means the step did not really commit that side;
                // there is nothing to undo. Say so loudly: it is the difference
                // between "compensated" and "nothing to compensate".
                log.warn("Skipping {} for order {}: no {} recorded at step {}",
                        action, order.getId(),
                        action.usesReservation() ? "reservation" : "payment reference",
                        state.getStep());
                continue;
            }
            compensations.enqueue(order.getId(), action, target, order.getTenantId(), now);
            queued = true;
        }

        outboxService.recordOrderEvent(MessagingTopics.ORDER_EVENTS,
                new OrderStatusEvent(order.getId(), order.getCustomerId(), previous.name(),
                        order.getStatus().name(), truncate(reason), now),
                order, "order.failed", correlationId);

        state.releaseClaim();
        log.info("Failed checkout for order {} at step {}: {}", order.getId(), state.getStep(), reason);
        return new Failure(OrderResponse.from(order), queued);
    }

    private Started describe(Order order) {
        SagaStep step = sagaStateRepository.findById(order.getId())
                .map(SagaState::getStep)
                .orElse(SagaStep.STARTED);
        return new Started(OrderResponse.from(order), step, true);
    }

    private SagaState requireState(String orderId) {
        return sagaStateRepository.findById(orderId)
                .orElseThrow(() -> new SagaAbandonedException(
                        "No saga state for order " + orderId));
    }

    private Order requireInFlight(String orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new SagaAbandonedException("Order " + orderId + " no longer exists"));
        if (!isInFlight(order.getStatus())) {
            throw new SagaAbandonedException(
                    "Order " + orderId + " is " + order.getStatus() + ", not in flight");
        }
        return order;
    }

    private static boolean isInFlight(Order.OrderStatus status) {
        return status == Order.OrderStatus.PENDING || status == Order.OrderStatus.RESERVED;
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return "unspecified";
        }
        return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
    }

    /** Result of the idempotency gate: the stored order plus where the saga is. */
    public record Started(OrderResponse order, SagaStep step, boolean existing) {
    }

    /** Outcome of a failure, and whether any undo work was queued. */
    public record Failure(OrderResponse order, boolean compensationQueued) {
    }
}
