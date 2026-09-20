package com.bellick.hub.profile.messaging;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Canonical event envelope consumed from {@code customer.profile.events}.
 *
 * <p>This is a duplicate of the envelope defined in integration-api — the two
 * services are independently deployable and share no code. In production the
 * contract would be governed by a Schema Registry (see docs/design.md §18).
 *
 * @param eventId       globally unique event id (consumer idempotency key)
 * @param type          domain event type
 * @param schemaVersion message contract version
 * @param occurredAt    business timestamp
 * @param customerId    partition key / business id
 * @param sourceChannel origin of the change
 * @param traceId       distributed trace id (M3 observability)
 * @param payload       full customer snapshot — event-carried state transfer
 */
public record Envelope(
        UUID eventId,
        String type,
        int schemaVersion,
        Instant occurredAt,
        String customerId,
        String sourceChannel,
        String traceId,
        JsonNode payload) {
}
