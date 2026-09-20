package com.bellick.hub.profile;

import com.bellick.hub.profile.model.DlqEvent;
import com.bellick.hub.profile.model.DlqStatus;
import com.bellick.hub.profile.model.ProfileStore;
import com.bellick.hub.profile.repository.DlqEventRepository;
import com.bellick.hub.profile.repository.ProcessedEventRepository;
import com.bellick.hub.profile.repository.ProfileStoreRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2 consumer end-to-end (Testcontainers Postgres + Kafka):
 * happy path persists the profile, duplicates are ignored (idempotency),
 * poison messages land on the DLQ ledger, and replay recovers them.
 */
@SpringBootTest(properties = {
        "hub.kafka.max-attempts=2",          // initial + 1 retry → fast DLQ in tests
        "hub.kafka.backoff-interval-ms=100"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ProfileConsumerIntegrationTest {

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
    MockMvc mockMvc;

    @Autowired
    ProfileStoreRepository profileStoreRepository;

    @Autowired
    ProcessedEventRepository processedEventRepository;

    @Autowired
    DlqEventRepository dlqEventRepository;

    @Test
    void happyPath_consumesEvent_andPersistsProfile() throws Exception {
        String customerId = "CUS-3001";
        UUID eventId = UUID.randomUUID();
        kafkaTemplate.send(TOPIC, customerId, envelope(eventId, customerId, "Happy Path")).get(10, java.util.concurrent.TimeUnit.SECONDS);

        await(() -> profileStoreRepository.findById(customerId).isPresent());
        ProfileStore store = profileStoreRepository.findById(customerId).orElseThrow();
        assertThat(store.getSnapshot().path("name").asText()).isEqualTo("Happy Path");
        assertThat(store.getVersion()).isEqualTo(0);
        assertThat(processedEventRepository.existsById(eventId)).isTrue();
    }

    @Test
    void duplicateEvent_isIgnored_idempotently() throws Exception {
        String customerId = "CUS-3002";
        UUID eventId = UUID.randomUUID();
        String payload = envelope(eventId, customerId, "Dupe");

        kafkaTemplate.send(TOPIC, customerId, payload).get(10, java.util.concurrent.TimeUnit.SECONDS);
        await(() -> processedEventRepository.existsById(eventId));
        kafkaTemplate.send(TOPIC, customerId, payload).get(10, java.util.concurrent.TimeUnit.SECONDS);

        // give the duplicate a moment to (not) be processed
        Thread.sleep(2000);
        assertThat(processedEventRepository.findAll()).hasSize(1);
        ProfileStore store = profileStoreRepository.findById(customerId).orElseThrow();
        assertThat(store.getVersion()).isEqualTo(0);   // second delivery did not touch it
    }

    @Test
    void poisonMessage_goesToDlq_andLandsInLedger() throws Exception {
        kafkaTemplate.send(TOPIC, "poison-key", "{not-valid-json{{{").get(10, java.util.concurrent.TimeUnit.SECONDS);

        await(() -> !dlqEventRepository.findAll().isEmpty());
        DlqEvent dead = dlqEventRepository.findAll().get(0);
        assertThat(dead.getStatus()).isEqualTo(DlqStatus.DLQED);
        assertThat(dead.getPayload()).contains("{not-valid-json{{{");
        assertThat(dead.getReason()).isNotBlank();   // DLT header carries the failure class name
    }

    @Test
    void replay_republishesToMainTopic_andMarksReplayed() throws Exception {
        String customerId = "CUS-3004";
        UUID eventId = UUID.randomUUID();
        String payload = envelope(eventId, customerId, "Replay Me");
        dlqEventRepository.save(new DlqEvent(eventId, TOPIC, payload, "test-only"));

        mockMvc.perform(post("/admin/dlq/" + eventId + "/replay"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REPLAYED"));

        await(() -> profileStoreRepository.findById(customerId).isPresent());
        assertThat(profileStoreRepository.findById(customerId).orElseThrow().getSnapshot().path("name").asText())
                .isEqualTo("Replay Me");
        assertThat(dlqEventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(DlqStatus.REPLAYED);
    }

    // ------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------

    private String envelope(UUID eventId, String customerId, String name) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerId", customerId);
        payload.put("name", name);
        payload.put("email", "x@example.com");
        payload.put("phone", "+64 21 000 0000");
        payload.put("addresses", List.of());
        payload.put("kycStatus", "PENDING");
        payload.put("version", 1);
        payload.put("updatedAt", Instant.now().toString());

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("eventId", eventId.toString());
        env.put("type", "CUSTOMER_CREATED");
        env.put("schemaVersion", 1);
        env.put("occurredAt", Instant.now().toString());
        env.put("customerId", customerId);
        env.put("sourceChannel", "BANKER_PORTAL");
        env.put("traceId", null);
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
