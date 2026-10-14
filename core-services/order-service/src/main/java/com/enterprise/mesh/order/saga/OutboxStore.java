package com.enterprise.mesh.order.saga;
import lombok.RequiredArgsConstructor;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.mesh.order.persistence.OutboxEvent;
import com.enterprise.mesh.order.persistence.OutboxEventRepository;

/**
 * Owns the outbox's transactional boundaries.
 *
 * <p>These operations live in a dedicated bean rather than on {@link OutboxRelay}
 * because Spring's {@code @Transactional} is applied by a proxy: a call such as
 * {@code this.markPublished(...)} would bypass that proxy and silently run
 * without a transaction. Keeping each short transaction here lets the relay hold
 * no database resources while it is talking to Kafka.
 */
@Service
@RequiredArgsConstructor
public class OutboxStore {

    private final OutboxEventRepository outboxRepository;
    /**
     * Claims up to {@code limit} rows for relay.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} means concurrent relay instances take
     * disjoint rows instead of blocking one another or publishing the same event
     * twice. The lock is released when this method returns.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimBatch(int limit) {
        return outboxRepository.findUnpublishedForUpdate(limit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public OutboxEvent find(String outboxId) {
        return outboxRepository.findById(outboxId).orElse(null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(String outboxId) {
        outboxRepository.findById(outboxId).ifPresent(event -> {
            event.markPublished(Instant.now());
            outboxRepository.save(event);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String outboxId, String error) {
        outboxRepository.findById(outboxId).ifPresent(event -> {
            event.recordFailure(error);
            outboxRepository.save(event);
        });
    }

    @Transactional(readOnly = true)
    public long backlogCount() {
        return outboxRepository.countByPublishedAtIsNull();
    }
}