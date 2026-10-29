package com.enterprise.mesh.order.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

/**
 * Executes queued compensating actions.
 *
 * <p>No transaction wraps a method here: the lease, the HTTP call and the
 * outcome are three separate pieces of work, and holding a database connection
 * across the middle one is exactly what the saga refactor removed.
 *
 * <p>Failure handling is by status class, not by guessing:
 *
 * <ul>
 *   <li>2xx - done.</li>
 *   <li>404 - nothing to undo (already released, voided or expired). Done.</li>
 *   <li>other 4xx - the target has rejected the action outright. Retrying would
 *       only bury the cause, so the row is abandoned with its reason and logged
 *       for manual reconciliation.</li>
 *   <li>5xx, timeouts, circuit breaker open - transient. Retained with
 *       exponential backoff, because the whole reason this queue exists is that
 *       the participant was unavailable when the checkout failed.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SagaCompensationRunner {

    private final SagaCompensationStore store;
    private final InventorySagaClient inventory;
    private final PaymentSagaClient payments;
    private final SagaProperties properties;

    /** Drains due work, used by the scheduled worker. */
    public int runDue(int batchSize) {
        Instant now = Instant.now();
        int attempted = 0;
        for (SagaCompensationStore.Work work : store.due(now, batchSize)) {
            if (attempt(work, now)) {
                attempted++;
            }
        }
        return attempted;
    }

    /**
     * Tries the compensations for one order immediately.
     *
     * <p>Called right after a checkout fails so the common case - the participant
     * is up, the undo succeeds - completes before the shopper's request returns.
     * Anything that does not land stays queued for the worker.
     */
    public int runForOrder(String orderId) {
        Instant now = Instant.now();
        int attempted = 0;
        for (SagaCompensationStore.Work work : store.dueForOrder(orderId)) {
            if (attempt(work, now)) {
                attempted++;
            }
        }
        return attempted;
    }

    private boolean attempt(SagaCompensationStore.Work work, Instant now) {
        Duration lease = Duration.ofSeconds(properties.getCompensation().getLeaseSeconds());
        if (!store.lease(work.id(), now, lease)) {
            log.debug("Compensation {} for order {} is leased by another worker",
                    work.action(), work.orderId());
            return false;
        }

        try {
            execute(work);
            store.complete(work.id());
            log.info("Compensated {} ({}) for order {}", work.action(), work.targetRef(), work.orderId());
        } catch (RestClientResponseException e) {
            handleHttpFailure(work, e);
        } catch (RuntimeException e) {
            // Timeouts, connection resets, an open circuit breaker: all worth
            // retrying once the dependency recovers.
            retry(work, describe(e));
        }
        return true;
    }

    private void handleHttpFailure(SagaCompensationStore.Work work, RestClientResponseException e) {
        int status = e.getStatusCode().value();
        String error = "HTTP " + status + ": " + e.getResponseBodyAsString();

        if (status == 404) {
            // The hold or authorisation no longer exists, which is the state this
            // action was trying to reach. Not an error.
            store.complete(work.id());
            log.info("Compensation {} for order {} had nothing to undo (404)",
                    work.action(), work.orderId());
        } else if (status >= 400 && status < 500 && status != 429) {
            store.abandon(work.id(), error);
        } else {
            retry(work, error);
        }
    }

    private void retry(SagaCompensationStore.Work work, String error) {
        int nextAttempt = work.attempts() + 1;
        Instant at = Instant.now().plus(backoff(nextAttempt));
        store.recordFailure(work.id(), error, at);
        log.warn("Compensation {} for order {} failed (attempt {}), retry after {}: {}",
                work.action(), work.orderId(), nextAttempt, at, error);
    }

    private void execute(SagaCompensationStore.Work work) {
        switch (work.action()) {
            case RELEASE_STOCK -> inventory.release(work.targetRef(), work.tenantId());
            case UNCONFIRM_STOCK -> inventory.unconfirm(work.targetRef(), work.tenantId());
            case VOID_PAYMENT -> payments.voidAuthorisation(work.targetRef(), work.tenantId());
            case REFUND_PAYMENT -> payments.refund(work.targetRef(), work.tenantId());
        }
    }

    /**
     * Exponential backoff, capped.
     *
     * <p>Capped rather than unbounded because the row is never given up: a
     * participant that is down for an hour must still be compensated when it
     * returns, and a 30-minute gap is a cheaper failure than losing the undo.
     */
    Duration backoff(int attempt) {
        long base = properties.getCompensation().getBaseDelaySeconds();
        long max = properties.getCompensation().getMaxDelaySeconds();
        long seconds = base;
        for (int i = 1; i < attempt && seconds < max; i++) {
            seconds *= 2;
        }
        return Duration.ofSeconds(Math.min(seconds, max));
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
