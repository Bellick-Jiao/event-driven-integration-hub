package com.bellick.hub.profile.service;

import com.bellick.hub.profile.model.DlqEvent;
import com.bellick.hub.profile.repository.DlqEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Persists dead-lettered events to the {@code dlq_events} ledger so they can
 * be inspected and replayed via {@code /admin/dlq}.
 *
 * <p>Handles even unparseable payloads (e.g. malformed JSON): the eventId is
 * derived deterministically from the payload bytes so the ledger always has a
 * stable primary key.
 */
@Component
public class DlqListener {

    private static final Logger log = LoggerFactory.getLogger(DlqListener.class);

    private final DlqEventRepository dlqEventRepository;
    private final ObjectMapper objectMapper;

    public DlqListener(DlqEventRepository dlqEventRepository, ObjectMapper objectMapper) {
        this.dlqEventRepository = dlqEventRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${hub.kafka.dlq-topic:customer.profile.events.dlq}",
            groupId = "profile-service-dlq-group",
            errorHandler = "dlqErrorHandler")
    public void onDlq(ConsumerRecord<String, String> record) {
        UUID eventId = parseEventId(record.value());
        String reason = record.headers().lastHeader("kafka_dlt-exception-fqcn") != null
                ? new String(record.headers().lastHeader("kafka_dlt-exception-fqcn").value(), StandardCharsets.UTF_8)
                : "consumer-error";

        DlqEvent event = dlqEventRepository.findById(eventId)
                .map(existing -> {
                    existing.setReason(reason);
                    return existing;
                })
                .orElseGet(() -> new DlqEvent(eventId, record.topic(), record.value(), reason));
        dlqEventRepository.save(event);

        log.warn("Event {} dead-lettered to ledger (reason: {})", eventId, reason);
    }

    private UUID parseEventId(String payload) {
        try {
            return UUID.fromString(objectMapper.readTree(payload).path("eventId").asText());
        } catch (Exception e) {
            // Unparseable payload (poison message): stable id from the bytes.
            return UUID.nameUUIDFromBytes(payload.getBytes(StandardCharsets.UTF_8));
        }
    }
}
