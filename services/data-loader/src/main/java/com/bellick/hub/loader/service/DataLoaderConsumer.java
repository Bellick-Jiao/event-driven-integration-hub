package com.bellick.hub.loader.service;

import com.bellick.hub.loader.messaging.Envelope;
import com.bellick.hub.loader.model.DataWarehouse;
import com.bellick.hub.loader.model.ProcessedEvent;
import com.bellick.hub.loader.repository.DataWarehouseRepository;
import com.bellick.hub.loader.repository.ProcessedEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Data platform" consumer: loads the event-carried snapshot into a wide
 * table, idempotently (design.md §6.3 / §9).
 *
 * <p>M3 observability: {@code eventId}, {@code customerId} and the envelope's
 * {@code traceId} are put on the SLF4J MDC so every JSON log line for this
 * event carries the correlation ids (Micrometer Tracing adds traceId/spanId
 * on top via Kafka instrumentation).
 */
@Component
public class DataLoaderConsumer {

    private static final Logger log = LoggerFactory.getLogger(DataLoaderConsumer.class);

    private final ObjectMapper objectMapper;
    private final DataWarehouseRepository warehouseRepository;
    private final ProcessedEventRepository processedEventRepository;

    public DataLoaderConsumer(ObjectMapper objectMapper,
                              DataWarehouseRepository warehouseRepository,
                              ProcessedEventRepository processedEventRepository) {
        this.objectMapper = objectMapper;
        this.warehouseRepository = warehouseRepository;
        this.processedEventRepository = processedEventRepository;
    }

    @KafkaListener(topics = "${hub.kafka.topic:customer.profile.events}")
    @Transactional
    public void onEvent(String payload) throws Exception {
        Envelope envelope = objectMapper.readValue(payload, Envelope.class);
        MDC.put("eventId", envelope.eventId().toString());
        MDC.put("customerId", envelope.customerId());
        if (envelope.traceId() != null) {
            MDC.put("traceId", envelope.traceId());
        }
        try {
            if (processedEventRepository.existsById(envelope.eventId())) {
                log.debug("Duplicate event {} ignored (idempotent ACK)", envelope.eventId());
                return;
            }

            DataWarehouse row = warehouseRepository.findById(envelope.customerId())
                    .map(existing -> {
                        existing.setSnapshot(envelope.payload());
                        return existing;
                    })
                    .orElseGet(() -> new DataWarehouse(envelope.customerId(), envelope.payload()));
            warehouseRepository.save(row);
            processedEventRepository.save(new ProcessedEvent(envelope.eventId(), envelope.customerId()));

            log.info("Loaded event {} into warehouse for customer {}", envelope.eventId(), envelope.customerId());
        } finally {
            MDC.clear();
        }
    }
}
