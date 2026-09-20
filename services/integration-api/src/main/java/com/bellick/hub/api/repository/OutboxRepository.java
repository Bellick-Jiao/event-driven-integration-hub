package com.bellick.hub.api.repository;

import com.bellick.hub.api.model.OutboxEvent;
import com.bellick.hub.api.model.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByAggregateIdOrderByCreatedAtAsc(String aggregateId);

    /** Oldest PENDING rows first, bounded by {@code pageable} — the M2 relay polls with this query. */
    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Pageable pageable);

    boolean existsByAggregateIdAndStatus(String aggregateId, OutboxStatus status);
}
