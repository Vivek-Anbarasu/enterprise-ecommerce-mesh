package com.enterprise.mesh.order.saga;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;

/**
 * One durable undo step owed for a failed checkout.
 *
 * <p>Rows are written in the same transaction that marks the order FAILED, so
 * the intent to compensate is exactly as durable as the failure. Execution is
 * separate: {@link SagaCompensationRunner} attempts it immediately and
 * {@link SagaCompensationWorker} retries whatever did not land.
 *
 * <p>{@code nextAttemptAt} doubles as the worker lease. A claimed row is pushed
 * into the future before the HTTP call is made, so two replicas cannot both
 * retry the same action and a crashed worker's row simply becomes due again.
 */
@Getter
@Entity
@Table(name = "saga_compensations")
public class SagaCompensation {

    /** Keeps a provider error message inside the column, whatever it returns. */
    private static final int MAX_ERROR_LENGTH = 1024;

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "order_id", nullable = false, length = 36)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32)
    private CompensationAction action;

    /** Reservation id for stock actions, payment id for money actions. */
    @Column(name = "target_ref", nullable = false, length = 128)
    private String targetRef;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 1024)
    private String lastError;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected SagaCompensation() {
    }

    public SagaCompensation(String id, String orderId, CompensationAction action,
                            String targetRef, String tenantId, Instant now) {
        this.id = id;
        this.orderId = orderId;
        this.action = action;
        this.targetRef = targetRef;
        this.tenantId = tenantId;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    /** Records a failed attempt and schedules the next one. */
    public void recordFailure(String error, Instant nextAttemptAt) {
        this.attempts++;
        this.lastError = truncate(error);
        this.nextAttemptAt = nextAttemptAt;
    }

    public void complete() {
        this.completedAt = Instant.now();
        this.lastError = null;
    }

    /**
     * Gives up on an action the target has definitively rejected (a 4xx).
     *
     * <p>Marked completed rather than left pending so a misconfigured tenant or a
     * payment in an unexpected state cannot be retried forever. The reason stays
     * on the row for the operator, and the runner logs it at error.
     */
    public void abandon(String error) {
        this.attempts++;
        this.lastError = truncate(error);
        this.completedAt = Instant.now();
    }

    public boolean isCompleted() {
        return completedAt != null;
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
