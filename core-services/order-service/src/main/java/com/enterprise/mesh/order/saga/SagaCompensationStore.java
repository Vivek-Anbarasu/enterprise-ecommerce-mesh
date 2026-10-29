package com.enterprise.mesh.order.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * All database access for the compensation queue.
 *
 * <p>Split from {@link SagaCompensationRunner} on purpose: every method here is a
 * short transaction, and the runner makes HTTP calls between them. A compensating
 * call inside a transaction would reintroduce the exact defect this refactor
 * removed - a database connection held across a network round trip.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SagaCompensationStore {

    private final SagaCompensationRepository repository;

    /**
     * Queues an undo action, ignoring a duplicate.
     *
     * <p>Called from inside the transaction that fails the order, so the queue
     * entry and the FAILED status commit together.
     */
    @Transactional
    public void enqueue(String orderId, CompensationAction action, String targetRef,
                        String tenantId, Instant now) {
        // OffsetDateTime at the native-query boundary: the column is TIMESTAMPTZ
        // and this is the type both Hibernate and the PostgreSQL driver bind
        // without inference.
        repository.insertIfAbsent(UUID.randomUUID().toString(), orderId, action.name(),
                targetRef, tenantId, now.atOffset(ZoneOffset.UTC));
    }

    /** Unfinished actions that are due, oldest first. */
    @Transactional(readOnly = true)
    public List<Work> due(Instant now, int batchSize) {
        return repository
                .findByCompletedAtIsNullAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                        now, PageRequest.of(0, batchSize))
                .stream()
                .map(Work::from)
                .toList();
    }

    /** Unfinished actions for one order, used to compensate immediately. */
    @Transactional(readOnly = true)
    public List<Work> dueForOrder(String orderId) {
        return repository.findByOrderIdAndCompletedAtIsNullOrderByCreatedAtAsc(orderId)
                .stream()
                .map(Work::from)
                .toList();
    }

    /** Claims one row for the calling worker. False means someone else has it. */
    @Transactional
    public boolean lease(String id, Instant now, Duration leaseFor) {
        return repository.lease(id, now, now.plus(leaseFor)) == 1;
    }

    @Transactional
    public void complete(String id) {
        repository.findById(id).ifPresent(SagaCompensation::complete);
    }

    @Transactional
    public void recordFailure(String id, String error, Instant nextAttemptAt) {
        repository.findById(id).ifPresent(work -> work.recordFailure(error, nextAttemptAt));
    }

    /**
     * Stops retrying an action the target has rejected outright.
     *
     * <p>Not the same as completing it: nothing was undone, so the row keeps the
     * reason and the runner logs it at error. Retrying a 4xx would just bury the
     * real problem under identical failures.
     */
    @Transactional
    public void abandon(String id, String error) {
        repository.findById(id).ifPresent(work -> {
            work.abandon(error);
            log.error("Giving up on saga compensation {} for order {} after {} attempt(s): {}",
                    work.getAction(), work.getOrderId(), work.getAttempts(), error);
        });
    }

    /** Immutable snapshot handed to the runner, so no entity escapes a transaction. */
    public record Work(String id, String orderId, CompensationAction action,
                       String targetRef, String tenantId, int attempts) {

        static Work from(SagaCompensation compensation) {
            return new Work(compensation.getId(), compensation.getOrderId(),
                    compensation.getAction(), compensation.getTargetRef(),
                    compensation.getTenantId(), compensation.getAttempts());
        }
    }
}
