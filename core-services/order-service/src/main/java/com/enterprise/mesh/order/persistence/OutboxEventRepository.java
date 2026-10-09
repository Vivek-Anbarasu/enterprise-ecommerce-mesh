package com.enterprise.mesh.order.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    /**
     * Oldest unpublished events first.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes this safe to run from
     * every replica: concurrent pollers take disjoint rows instead of blocking
     * on each other or double-publishing the same event.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findUnpublishedForUpdate(@Param("limit") int limit);

    /** Rows that failed repeatedly; surfaced for alerting rather than retried forever. */
    @Query("""
            select o from OutboxEvent o
            where o.publishedAt is null and o.attempts >= :threshold
            order by o.createdAt asc
            """)
    List<OutboxEvent> findPoisoned(@Param("threshold") int threshold, Pageable pageable);

    long countByPublishedAtIsNull();
}
