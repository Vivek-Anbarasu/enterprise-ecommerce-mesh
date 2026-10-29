package com.enterprise.mesh.order.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fails checkouts whose orchestrator died mid-flight, then compensates them.
 *
 * <p>Persisting the saga step would be pointless without this: a crashed process
 * leaves an order {@code RESERVED} with stock held and, after authorisation, a
 * payment hold nobody remembers. The claim lease tells us the difference between
 * "busy" and "gone", and the recorded step tells the state store exactly which
 * undoing is owed.
 *
 * <p>Recovery is deliberately conservative: an abandoned saga is failed, never
 * resumed. Resuming would mean a second process continuing another's work with
 * no way to know which remote calls had already returned; failing and
 * compensating converges on a clean state that the customer can retry.
 */
@Component
@ConditionalOnProperty(prefix = "mesh.saga.recovery", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class StaleSagaSweeper {

    private final SagaStateRepository sagaStateRepository;
    private final SagaStateStore stateStore;
    private final SagaCompensationRunner compensations;
    private final SagaProperties properties;

    @Scheduled(fixedDelayString = "${mesh.saga.recovery.interval-ms:60000}")
    public void sweep() {
        Instant staleBefore = Instant.now().minus(properties.getClaimLease());

        List<SagaState> abandoned;
        try {
            abandoned = sagaStateRepository.findAbandoned(staleBefore, SagaStep.COMPLETED,
                    PageRequest.of(0, properties.getRecovery().getBatchSize()));
        } catch (RuntimeException e) {
            log.error("Abandoned-saga scan failed; will retry on the next sweep", e);
            return;
        }
        if (abandoned.isEmpty()) {
            return;
        }

        log.warn("Recovering {} abandoned checkout saga(s)", abandoned.size());
        for (SagaState saga : abandoned) {
            try {
                // Re-checked inside the transaction: the holder may have finished
                // between the scan and this call.
                if (stateStore.abandon(saga.getOrderId(), staleBefore)) {
                    compensations.runForOrder(saga.getOrderId());
                }
            } catch (RuntimeException e) {
                // One poisoned row must not stop the rest of the batch; the claim
                // is still stale, so the next sweep retries it.
                log.error("Could not recover abandoned saga for order {}", saga.getOrderId(), e);
            }
        }
    }
}
