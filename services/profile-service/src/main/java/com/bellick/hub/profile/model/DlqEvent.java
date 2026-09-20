package com.bellick.hub.profile.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Dead-letter ledger: every event that exhausted consumer retries is recorded
 * here and can be replayed via {@code POST /admin/dlq/{eventId}/replay}
 * (design.md §6.2 — DLQ + replay).
 */
@Entity
@Table(name = "dlq_events")
public class DlqEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "topic", nullable = false)
    private String topic;

    /** Original event envelope (raw bytes, may be a non-JSON poison message) — replayed verbatim. */
    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "reason")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DlqStatus status;

    @Column(name = "retried_at")
    private Instant retriedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    protected DlqEvent() {
        // for JPA
    }

    public DlqEvent(UUID eventId, String topic, String payload, String reason) {
        this.eventId = eventId;
        this.topic = topic;
        this.payload = payload;
        this.reason = reason;
        this.status = DlqStatus.DLQED;
    }

    public void markReplayed() {
        this.status = DlqStatus.REPLAYED;
        this.retriedAt = Instant.now();
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getTopic() {
        return topic;
    }

    public String getPayload() {
        return payload;
    }

    public String getReason() {
        return reason;
    }

    public DlqStatus getStatus() {
        return status;
    }

    public Instant getRetriedAt() {
        return retriedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
