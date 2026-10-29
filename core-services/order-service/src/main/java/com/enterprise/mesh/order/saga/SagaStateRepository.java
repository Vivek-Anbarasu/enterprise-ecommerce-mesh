package com.enterprise.mesh.order.saga;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SagaStateRepository extends JpaRepository<SagaState, String> {

    /**
     * In-flight sagas whose claim lease has expired, oldest first.
     *
     * <p>Ordered by {@code claimed_at} so a backlog is drained roughly in the
     * order it accumulated. The terminal-step predicate keeps completed rows out
     * of the scan even if a released claim left a timestamp behind.
     */
    @Query("""
            select s from SagaState s
            where s.step <> :completed
              and s.claimToken is not null
              and s.claimedAt < :staleBefore
            order by s.claimedAt asc
            """)
    List<SagaState> findAbandoned(@Param("staleBefore") Instant staleBefore,
                                  @Param("completed") SagaStep completed,
                                  Pageable pageable);
}
