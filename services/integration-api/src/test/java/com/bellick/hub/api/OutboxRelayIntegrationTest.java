package com.bellick.hub.api;

import com.bellick.hub.api.model.OutboxEvent;
import com.bellick.hub.api.model.OutboxStatus;
import com.bellick.hub.api.repository.OutboxRepository;
import com.bellick.hub.api.service.OutboxRelay;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2 relay end-to-end: POST → outbox row (PENDING) → relay publishes to Kafka
 * with the transactional producer → row flips to PUBLISHED → the topic holds
 * the event envelope, partitioned by customerId.
 *
 * <p>Needs Docker. The scheduled relay is slowed to 1h so the test triggers
 * {@link OutboxRelay#relay()} deterministically.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "hub.relay.poll-interval-ms=3600000")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class OutboxRelayIntegrationTest {

    private static final String TOPIC = "customer.profile.events";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hub")
            .withUsername("hub")
            .withPassword("hub-dev");

    @Container
    @ServiceConnection
    static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @Autowired
    MockMvc mockMvc;

    @Autowired
    OutboxRepository outboxRepository;

    @Autowired
    OutboxRelay outboxRelay;

    @Test
    void relay_publishesPendingOutbox_toKafka_andMarksPublished() throws Exception {
        String customerId = "CUS-2001";

        mockMvc.perform(post("/api/v1/customers")
                        .header("Idempotency-Key", "key-relay-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "CUS-2001",
                                  "name": "Relay Test",
                                  "email": "relay@example.com",
                                  "addresses": [{"type": "HOME", "line1": "2 Queen St", "city": "Auckland", "postcode": "1010"}]
                                }
                                """))
                .andExpect(status().isAccepted());

        List<OutboxEvent> before = outboxRepository.findByAggregateIdOrderByCreatedAtAsc(customerId);
        assertThat(before).hasSize(1);
        assertThat(before.get(0).getStatus()).isEqualTo(OutboxStatus.PENDING);

        outboxRelay.relay();

        List<OutboxEvent> after = outboxRepository.findByAggregateIdOrderByCreatedAtAsc(customerId);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(after.get(0).getPublishedAt()).isNotNull();

        ConsumerRecord<String, String> record = awaitRecordForKey(customerId);
        assertThat(record.key()).isEqualTo(customerId);                       // partition key = customerId
        assertThat(record.value())
                .contains("CUS-2001")                                          // envelope fields
                .contains("CUSTOMER_CREATED")
                .contains("schemaVersion")
                .contains("BANKER_PORTAL");
    }

    // ------------------------------------------------------------
    // helper: poll the topic with a throwaway consumer until the record shows up
    // ------------------------------------------------------------

    private ConsumerRecord<String, String> awaitRecordForKey(String customerId) throws Exception {
        Properties props = new Properties();
        props.put("bootstrap.servers", KAFKA.getBootstrapServers());
        props.put("group.id", "relay-test-" + UUID.randomUUID());
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());
        props.put("auto.offset.reset", "earliest");
        props.putAll(Map.of("enable.auto.commit", "false"));

        long deadline = System.currentTimeMillis() + 20_000;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TOPIC));
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (customerId.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No record with key " + customerId + " on topic " + TOPIC + " within 20s");
    }
}
