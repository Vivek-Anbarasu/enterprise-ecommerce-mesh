package com.enterprise.mesh.order.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Write-only repository for the transactional outbox.
 *
 * <p>Deliberately declares no queries beyond those inherited from
 * {@link JpaRepository}: delivery is Debezium's job, so nothing in this
 * application ever reads the table back. The relay's {@code FOR UPDATE SKIP
 * LOCKED} claim query, its poison-row scan and its unpublished-count are all
 * gone with the relay, and adding read queries here would only invite a second
 * reader that competes with Debezium for the WAL.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
}