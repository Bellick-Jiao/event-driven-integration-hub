package com.bellick.hub.profile.controller;

import com.bellick.hub.profile.model.DlqEvent;
import com.bellick.hub.profile.repository.DlqEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dead-letter operations (design.md §6.2): inspect the DLQ ledger and replay
 * a dead-lettered event back to the main topic after the underlying issue is
 * fixed — the operational "DLQ + replay" story.
 */
@RestController
@RequestMapping("/admin/dlq")
public class DlqController {

    private final DlqEventRepository dlqEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public DlqController(DlqEventRepository dlqEventRepository,
                         KafkaTemplate<String, String> kafkaTemplate,
                         ObjectMapper objectMapper,
                         @Value("${hub.kafka.topic:customer.profile.events}") String topic) {
        this.dlqEventRepository = dlqEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @GetMapping
    public List<DlqEvent> list() {
        return dlqEventRepository.findAllByOrderByCreatedAtDesc();
    }

    @PostMapping("/{eventId}/replay")
    public ResponseEntity<Map<String, String>> replay(@PathVariable UUID eventId) {
        DlqEvent event = dlqEventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "DLQ event not found: " + eventId));

        String customerId = parseCustomerId(event.getPayload());
        kafkaTemplate.send(topic, customerId, event.getPayload());

        event.markReplayed();
        dlqEventRepository.save(event);

        return ResponseEntity.accepted().body(Map.of(
                "eventId", eventId.toString(),
                "status", "REPLAYED",
                "topic", topic));
    }

    private String parseCustomerId(String payload) {
        try {
            return objectMapper.readTree(payload).path("customerId").asText();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Payload cannot be replayed (unparseable): " + eventIdOf(payload));
        }
    }

    private String eventIdOf(String payload) {
        return UUID.nameUUIDFromBytes(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
}
