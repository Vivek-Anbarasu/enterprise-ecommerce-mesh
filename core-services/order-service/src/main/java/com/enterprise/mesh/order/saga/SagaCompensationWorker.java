package com.enterprise.mesh.order.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retries compensating actions that did not land when the checkout failed.
 *
 * <p>This is the difference between "we tried to undo it" and "it is undone".
 * A declined payment whose stock release timed out still has the release queued;
 * without a worker, the only trace would be a log line and a hold that expires
 * fifteen minutes later.
 *
 * <p>Runs in every replica. Concurrency is handled per row by the lease in
 * {@link SagaCompensationStore#lease}, not by electing a leader.
 */
@Component
@ConditionalOnProperty(prefix = "mesh.saga.compensation", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class SagaCompensationWorker {

    private final SagaCompensationRunner runner;
    private final SagaProperties properties;

    @Scheduled(fixedDelayString = "${mesh.saga.compensation.interval-ms:30000}")
    public void drain() {
        try {
            int attempted = runner.runDue(properties.getCompensation().getBatchSize());
            if (attempted > 0) {
                log.info("Compensation worker attempted {} queued action(s)", attempted);
            }
        } catch (RuntimeException e) {
            // A failed sweep must not stop later sweeps; the rows are durable and
            // still due, so the next tick picks them up.
            log.error("Compensation sweep failed; queued actions remain due", e);
        }
    }
}
