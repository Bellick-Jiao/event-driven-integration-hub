package com.bellick.hub.profile.service;

import com.bellick.hub.profile.messaging.Envelope;
import com.bellick.hub.profile.model.ProcessedEvent;
import com.bellick.hub.profile.model.ProfileStore;
import com.bellick.hub.profile.repository.ProcessedEventRepository;
import com.bellick.hub.profile.repository.ProfileStoreRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Main consumer of {@code customer.profile.events} ("core system" simulation).
 *
 * <p>Processing contract (design.md §6.2/§9/§10):
 * <ol>
 *   <li><b>Idempotent</b>: an eventId already in {@code processed_events} is
 *       acknowledged without side effects — safe under at-least-once;</li>
 *   <li><b>ECST</b>: the payload already carries the full customer snapshot,
 *       so we upsert it with no callback to the source API;</li>
 *   <li><b>Failure path</b>: any exception propagates to the container's
 *       {@code DefaultErrorHandler} → exponential-backoff retry → DLQ topic +
 *       {@code dlq_events} ledger after max attempts.</li>
 * </ol>
 */
@Component
public class ProfileEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProfileEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final ProfileStoreRepository profileStoreRepository;
    private final ProcessedEventRepository processedEventRepository;

    public ProfileEventConsumer(ObjectMapper objectMapper,
                                ProfileStoreRepository profileStoreRepository,
                                ProcessedEventRepository processedEventRepository) {
        this.objectMapper = objectMapper;
        this.profileStoreRepository = profileStoreRepository;
        this.processedEventRepository = processedEventRepository;
    }

    @KafkaListener(topics = "${hub.kafka.topic:customer.profile.events}")
    @Transactional
    public void onEvent(String payload) throws Exception {
        Envelope envelope = objectMapper.readValue(payload, Envelope.class);

        if (processedEventRepository.existsById(envelope.eventId())) {
            log.debug("Duplicate event {} ignored (idempotent ACK)", envelope.eventId());
            return;
        }

        ProfileStore store = profileStoreRepository.findById(envelope.customerId())
                .map(existing -> {
                    existing.setSnapshot(envelope.payload());
                    existing.incrementVersion();
                    return existing;
                })
                .orElseGet(() -> new ProfileStore(envelope.customerId(), envelope.payload(), 0L));
        profileStoreRepository.save(store);
        processedEventRepository.save(new ProcessedEvent(envelope.eventId(), envelope.customerId()));

        log.info("Processed {} for customer {} (schema v{})", envelope.eventId(), envelope.customerId(),
                envelope.schemaVersion());
    }
}
