package com.enterprise.mesh.order.saga;

import java.time.Duration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Checkout-saga configuration, bound from {@code mesh.saga.*}.
 *
 * <p>The timeout pair is the fix for the saga's oldest defect: the orchestrator
 * used to build a bare {@code RestClient} with no connect or read budget, so a
 * hung inventory service could pin a request (and, before the transaction was
 * split, a database connection) indefinitely. The values are deliberately short -
 * a checkout step that takes longer than a few seconds has already lost the
 * shopper.
 *
 * <p>{@code claimLease} must exceed the worst-case time to run every step
 * (roughly four read timeouts plus local work). Too short and a slow-but-alive
 * checkout is swept as abandoned; too long and a crashed checkout holds stock
 * for longer than necessary.
 */
@ConfigurationProperties(prefix = "mesh.saga")
@Getter
@Setter
public class SagaProperties {

    /** Inventory hold lifetime; the sweeper reclaims anything past this. */
    private long reservationTtlSeconds = 900;

    /** TCP connect budget for a single inventory or payment call. */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /**
     * Response budget for a single inventory or payment call.
     *
     * <p>Five seconds, not thirty: the saga is synchronous, so this is time the
     * shopper's HTTP request is also waiting. The circuit breaker, not a long
     * timeout, is what absorbs a slow dependency.
     */
    private Duration readTimeout = Duration.ofSeconds(5);

    /** How long one orchestrator may hold a saga before it is considered dead. */
    private Duration claimLease = Duration.ofMinutes(2);

    private final Compensation compensation = new Compensation();

    private final Recovery recovery = new Recovery();

    /** Retry loop for queued compensating actions. */
    @Getter
    @Setter
    public static class Compensation {

        private boolean enabled = true;

        private long intervalMs = 30_000;

        private int batchSize = 50;

        /** First retry delay; doubles per attempt. */
        private long baseDelaySeconds = 30;

        /** Ceiling for the backoff, so a long outage is retried, not abandoned. */
        private long maxDelaySeconds = 900;

        /** Worker lease for one action; must exceed the call's read timeout. */
        private long leaseSeconds = 60;
    }

    /** Sweep for sagas whose orchestrator died mid-flight. */
    @Getter
    @Setter
    public static class Recovery {

        private boolean enabled = true;

        private long intervalMs = 60_000;

        private int batchSize = 50;
    }
}
