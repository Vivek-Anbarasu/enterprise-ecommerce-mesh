package com.enterprise.mesh.order.saga;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SagaCompensationRepository extends JpaRepository<SagaCompensation, String> {

    List<SagaCompensation> findByCompletedAtIsNullAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            Instant now, Pageable page);

    List<SagaCompensation> findByOrderIdAndCompletedAtIsNullOrderByCreatedAtAsc(String orderId);

    /**
     * Queues an undo action unless one is already queued for this order.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than a read-then-insert: the unique
     * index is the guarantee, and a failed insert would mark the surrounding order
     * transaction rollback-only. A retried failure path is expected here, not
     * exceptional, so the collision must be silent.
     */
    @Modifying
    @Query(value = """
            insert into saga_compensations
                (id, order_id, action, target_ref, tenant_id, attempts, next_attempt_at, created_at)
            values (:id, :orderId, :action, :targetRef, :tenantId, 0, :now, :now)
            on conflict (order_id, action) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") String id,
                       @Param("orderId") String orderId,
                       @Param("action") String action,
                       @Param("targetRef") String targetRef,
                       @Param("tenantId") String tenantId,
                       @Param("now") OffsetDateTime now);

    /**
     * Takes the worker lease for one row, atomically.
     *
     * <p>The predicate on {@code nextAttemptAt} is the whole concurrency story:
     * exactly one replica's update matches, so exactly one replica makes the call.
     * A crashed holder needs no cleanup - its row comes due again when the leased
     * window passes.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update SagaCompensation c
               set c.nextAttemptAt = :leasedUntil
             where c.id = :id
               and c.completedAt is null
               and c.nextAttemptAt <= :now
            """)
    int lease(@Param("id") String id,
              @Param("now") Instant now,
              @Param("leasedUntil") Instant leasedUntil);
}
