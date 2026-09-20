package com.bellick.hub.api.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the canonical event envelope: JSON shape and round-trip.
 * The envelope is what the M2 relay publishes to Kafka verbatim.
 */
class EventEnvelopeTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesToCanonicalShape() throws Exception {
        UUID eventId = UUID.randomUUID();
        JsonNode payload = objectMapper.readTree("{\"customerId\":\"CUS-0001\",\"kycStatus\":\"VERIFIED\"}");
        EventEnvelope envelope = new EventEnvelope(
                eventId, "CUSTOMER_CREATED", 1,
                Instant.parse("2026-09-15T00:00:00Z"),
                "CUS-0001", "BANKER_PORTAL", null, payload);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(envelope));

        assertThat(json.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(json.get("type").asText()).isEqualTo("CUSTOMER_CREATED");
        assertThat(json.get("schemaVersion").asInt()).isEqualTo(EventEnvelope.CURRENT_SCHEMA_VERSION);
        assertThat(json.get("customerId").asText()).isEqualTo("CUS-0001");
        assertThat(json.get("sourceChannel").asText()).isEqualTo("BANKER_PORTAL");
        assertThat(json.get("payload").get("customerId").asText()).isEqualTo("CUS-0001");
    }

    @Test
    void roundTripsThroughJson() throws Exception {
        EventEnvelope original = new EventEnvelope(
                UUID.randomUUID(), "CUSTOMER_UPDATED", 1,
                Instant.parse("2026-09-15T01:02:03Z"),
                "CUS-0002", "BANKER_PORTAL", "trace-123",
                objectMapper.readTree("{\"customerId\":\"CUS-0002\",\"phone\":\"+64 21 111 1111\"}"));

        EventEnvelope restored = objectMapper.readValue(
                objectMapper.writeValueAsString(original), EventEnvelope.class);

        assertThat(restored).isEqualTo(original);
    }
}
