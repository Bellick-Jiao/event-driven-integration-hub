package com.bellick.hub.api;

import com.bellick.hub.api.model.OutboxEvent;
import com.bellick.hub.api.model.OutboxStatus;
import com.bellick.hub.api.repository.CustomerRepository;
import com.bellick.hub.api.repository.OutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test with a real Postgres (Testcontainers).
 *
 * <p>Verifies the M1 core contract: one transaction writes customer + outbox;
 * idempotency keys are replayed without duplication; conflicts are 409;
 * validation failures are 400 and must NOT leave any rows behind.
 *
 * <p>Requires a running Docker daemon (tests are skipped automatically otherwise).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CustomerApiIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hub")
            .withUsername("hub")
            .withPassword("hub-dev");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    CustomerRepository customerRepository;

    @Autowired
    OutboxRepository outboxRepository;

    private static final String CREATE_BODY = """
            {
              "customerId": "CUS-1001",
              "name": "Jane Doe",
              "email": "jane@example.com",
              "phone": "+64 21 000 0000",
              "addresses": [{"type": "HOME", "line1": "1 Queen St", "city": "Auckland", "postcode": "1010"}],
              "kycStatus": "VERIFIED"
            }
            """;

    @Test
    void createCustomer_persistsCustomerAndOutboxAtomically_returns202() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .header("Idempotency-Key", "key-create-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/customers/CUS-1001"))
                .andExpect(jsonPath("$.customerId").value("CUS-1001"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.eventId").isNotEmpty());

        assertThat(customerRepository.findByCustomerId("CUS-1001")).isPresent();

        List<OutboxEvent> events = outboxRepository.findByAggregateIdOrderByCreatedAtAsc("CUS-1001");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getStatus()).isEqualTo(OutboxStatus.PENDING);
        // The outbox row carries the full event envelope, ready for the M2 relay.
        assertThat(events.get(0).getPayload()).contains("CUSTOMER_CREATED", "CUS-1001", "schemaVersion");
    }

    @Test
    void repeatedIdempotencyKey_replaysFirstResponse_withoutDuplicates() throws Exception {
        String firstEventId = createAndGetEventId("CUS-1002", "key-create-2");

        mockMvc.perform(post("/api/v1/customers")
                        .header("Idempotency-Key", "key-create-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("CUS-1001", "CUS-1002")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value(firstEventId));

        assertThat(outboxRepository.findByAggregateIdOrderByCreatedAtAsc("CUS-1002")).hasSize(1);
    }

    @Test
    void sameIdempotencyKey_differentBody_returns409() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .header("Idempotency-Key", "key-create-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("CUS-1001", "CUS-1003")))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/customers")
                        .header("Idempotency-Key", "key-create-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("CUS-1001", "CUS-1003").replace("Jane Doe", "Jane Roe")))
                .andExpect(status().isConflict());
    }

    @Test
    void duplicateCustomerId_differentKey_returns409() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .header("Idempotency-Key", "key-create-4a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("CUS-1001", "CUS-1004")))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/customers")
                        .header("Idempotency-Key", "key-create-4b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("CUS-1001", "CUS-1004")))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidPayload_returns400_andWritesNothing() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .header("Idempotency-Key", "key-create-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": "bad-id", "name": "", "email": "not-an-email"}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(customerRepository.findByCustomerId("bad-id")).isNotPresent();
        assertThat(outboxRepository.findByAggregateIdOrderByCreatedAtAsc("bad-id")).isEmpty();
    }

    @Test
    void missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("CUS-1001", "CUS-1006")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getCustomer_andEventHistory() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .header("Idempotency-Key", "key-create-7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("CUS-1001", "CUS-1007")))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/v1/customers/CUS-1007"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("CUS-1007"))
                .andExpect(jsonPath("$.name").value("Jane Doe"))
                .andExpect(jsonPath("$.processingStatus").value("PENDING"))
                .andExpect(jsonPath("$.version").value(0));

        mockMvc.perform(get("/api/v1/customers/CUS-1007/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("CUSTOMER_CREATED"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));

        mockMvc.perform(get("/api/v1/customers/CUS-9999"))
                .andExpect(status().isNotFound());
    }

    private String createAndGetEventId(String customerId, String key) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/customers")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("CUS-1001", customerId)))
                .andExpect(status().isAccepted())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return body.get("eventId").asText();
    }
}
