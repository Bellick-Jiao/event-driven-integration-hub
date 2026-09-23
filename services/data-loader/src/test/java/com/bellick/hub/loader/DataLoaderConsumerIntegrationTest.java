package com.bellick.hub.loader;

import com.bellick.hub.loader.model.DataWarehouse;
import com.bellick.hub.loader.model.ProcessedEvent;
import com.bellick.hub.loader.repository.DataWarehouseRepository;
import com.bellick.hub.loader.repository.ProcessedEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3 fan-out end-to-end (Testcontainers Postgres + Kafka):
 * data-loader consumes the same topic in its own group, idempotently,
 * and writes the wide warehouse table.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DataLoaderConsumerIntegrationTest {

    private static final String TOPIC = "customer.profile.events";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hub")
            .withUsername("hub")
            .withPassword("hub-dev");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    DataWarehouseRepository warehouseRepository;

    @Autowired
    ProcessedEventRepository processedEventRepository;

    @Test
    void happyPath_consumesEvent_writesWideTable() throws Exception {
        String customerId = "CUS-4001";
        UUID eventId = UUID.randomUUID();
        kafkaTemplate.send(TOPIC, customerId, envelope(eventId, customerId, "Warehouse Me"))
                .get(10, java.util.concurrent.TimeUnit.SECONDS);

        await(() -> warehouseRepository.findById(customerId).isPresent());
        DataWarehouse row = warehouseRepository.findById(customerId).orElseThrow();
        assertThat(row.getSnapshot().path("name").asText()).isEqualTo("Warehouse Me");
        assertThat(processedEventRepository.existsById(eventId)).isTrue();
    }

    @Test
    void duplicateEvent_isIgnored_idempotently() throws Exception {
        String customerId = "CUS-4002";
        UUID eventId = UUID.randomUUID();
        String payload = envelope(eventId, customerId, "Dupe");

        kafkaTemplate.send(TOPIC, customerId, payload).get(10, java.util.concurrent.TimeUnit.SECONDS);
        await(() -> processedEventRepository.existsById(eventId));
        kafkaTemplate.send(TOPIC, customerId, payload).get(10, java.util.concurrent.TimeUnit.SECONDS);

        Thread.sleep(2000);
        // Count only rows for THIS customerId: other test methods share the
        // same Spring context / database, so findAll() would include theirs.
        long rowsForThisCustomer = processedEventRepository.findAll().stream()
                .filter(row -> customerId.equals(row.getCustomerId()))
                .count();
        assertThat(rowsForThisCustomer).isEqualTo(1);
    }

    // ------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------

    private String envelope(UUID eventId, String customerId, String name) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerId", customerId);
        payload.put("name", name);
        payload.put("email", "x@example.com");
        payload.put("addresses", List.of());

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("eventId", eventId.toString());
        env.put("type", "CUSTOMER_CREATED");
        env.put("schemaVersion", 1);
        env.put("occurredAt", Instant.now().toString());
        env.put("customerId", customerId);
        env.put("sourceChannel", "BANKER_PORTAL");
        env.put("traceId", UUID.randomUUID().toString());
        env.put("payload", payload);
        return objectMapper.writeValueAsString(env);
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Condition not met within 20s");
    }
}
