package com.bellick.hub.api.messaging;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Canonical event envelope written to the outbox and (from M2) published to
 * Kafka as-is. See docs/design.md §9.
 *
 * @param eventId      globally unique event id (also the consumer idempotency key)
 * @param type         domain event type (CUSTOMER_CREATED / CUSTOMER_UPDATED)
 * @param schemaVersion message contract version — bump on backward-compatible evolution
 * @param occurredAt   business timestamp
 * @param customerId   partition key (per-customer ordering on the topic)
 * @param sourceChannel origin of the change (BANKER_PORTAL for now)
 * @param traceId      distributed trace id (populated from M3 observability)
 * @param payload      the full customer snapshot — event-carried state transfer
 */
public record EventEnvelope(
        UUID eventId,
        String type,
        int schemaVersion,
        Instant occurredAt,
        String customerId,
        String sourceChannel,
        String traceId,
        JsonNode payload) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String SOURCE_CHANNEL = "BANKER_PORTAL";
}
