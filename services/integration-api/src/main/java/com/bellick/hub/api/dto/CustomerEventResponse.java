package com.bellick.hub.api.dto;

import com.bellick.hub.api.model.EventType;
import com.bellick.hub.api.model.OutboxStatus;

import java.time.Instant;
import java.util.UUID;

/** One entry of the event history (audit / replay inspection). */
public record CustomerEventResponse(
        UUID eventId,
        EventType type,
        OutboxStatus status,
        Instant createdAt) {
}
