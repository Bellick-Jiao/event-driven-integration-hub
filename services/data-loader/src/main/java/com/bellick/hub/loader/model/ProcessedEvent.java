package com.bellick.hub.loader.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Idempotency ledger: one row per consumed {@code eventId}. A redelivered
 * event finds its row and is acknowledged without side effects.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @CreationTimestamp
    @Column(name = "processed_at", updatable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
        // for JPA
    }

    public ProcessedEvent(UUID eventId, String customerId) {
        this.eventId = eventId;
        this.customerId = customerId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
