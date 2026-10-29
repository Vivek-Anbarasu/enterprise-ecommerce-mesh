package com.enterprise.mesh.order.saga;

import java.time.Duration;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;

/**
 * Durable progress record for one checkout.
 *
 * <p>One row per order. It exists so the orchestrator can answer three questions
 * after a restart, none of which {@code orders.status} alone can:
 *
 * <ol>
 *   <li>How far did this checkout get? ({@link #step})</li>
 *   <li>Is someone driving it right now? ({@link #claimToken})</li>
 *   <li>If that someone died, how long ago? ({@link #claimedAt})</li>
 * </ol>
 *
 * <p>The claim exists because a client retry with the same idempotency key now
 * finds the in-flight order instead of creating a new one. Without a claim, two
 * concurrent requests would both re-run the saga, and though every remote step
 * is idempotent, two parallel captures can still race the payment state machine.
 * The {@code @Version} column makes the claim atomic: the loser's write fails
 * rather than overwriting the winner's token.
 *
 * <p>The claim is a lease, not a lock. It is never released implicitly on crash;
 * it simply goes stale after {@code mesh.saga.claim-lease}, at which point
 * {@link StaleSagaSweeper} may fail the order and queue its compensations.
 */
@Getter
@Entity
@Table(name = "saga_state")
public class SagaState {

    @Id
    @Column(name = "order_id", nullable = false, updatable = false, length = 36)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "step", nullable = false, length = 24)
    private SagaStep step;

    /** Null when nobody holds the saga. */
    @Column(name = "claim_token", length = 36)
    private String claimToken;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** Required by JPA. */
    protected SagaState() {
    }

    public SagaState(String orderId) {
        this.orderId = orderId;
        this.step = SagaStep.STARTED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Advances the step; never moves it backwards. */
    public void advanceTo(SagaStep next) {
        if (next.ordinal() <= step.ordinal()) {
            return;
        }
        this.step = next;
        this.updatedAt = Instant.now();
    }

    public void claim(String token, Instant now) {
        this.claimToken = token;
        this.claimedAt = now;
        this.updatedAt = now;
    }

    public void releaseClaim() {
        this.claimToken = null;
        this.claimedAt = null;
        this.updatedAt = Instant.now();
    }

    /**
     * True if the claim was taken recently enough that its holder may still be
     * working. A stale claim is not "free" - the sweeper deliberately routes it
     * through the failure path rather than letting a second orchestrator adopt a
     * half-finished saga.
     */
    public boolean hasLiveClaim(Instant now, Duration lease) {
        if (claimToken == null || claimedAt == null) {
            return false;
        }
        return claimedAt.isAfter(now.minus(lease));
    }

    public boolean claimIsStale(Instant staleBefore) {
        return claimToken != null && claimedAt != null && claimedAt.isBefore(staleBefore);
    }
}
