package com.bellick.hub.api.model;

/** Outbox row lifecycle. M2 relay flips PENDING → PUBLISHED after a successful Kafka send. */
public enum OutboxStatus {
    PENDING,
    PUBLISHED
}
