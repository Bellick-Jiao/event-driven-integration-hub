package com.bellick.hub.api.service;

import com.bellick.hub.api.messaging.EventPublisher;
import com.bellick.hub.api.model.OutboxEvent;
import com.bellick.hub.api.model.OutboxStatus;
import com.bellick.hub.api.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Outbox relay (M2): polls the oldest {@code PENDING} rows in the outbox
 * table and publishes them to Kafka with a transactional producer.
 *
 * <p>This is the second half of the transactional outbox pattern:
 * <ol>
 *   <li>write side (M1): {@code customer} + {@code outbox} commit together;</li>
 *   <li>this relay: publish the row, then flip it to {@code PUBLISHED}.</li>
 * </ol>
 * If publishing fails the row stays {@code PENDING} and the next poll retries
 * it (no message loss). A crash between the Kafka commit and the DB update can
 * redeliver — the consumer's idempotency makes that safe (at-least-once).
 */
@Component
@ConditionalOnProperty(name = "hub.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final EventPublisher eventPublisher;
    private final String topic;
    private final int batchSize;

    public OutboxRelay(OutboxRepository outboxRepository,
                       EventPublisher eventPublisher,
                       @Value("${hub.relay.topic:customer.profile.events}") String topic,
                       @Value("${hub.relay.batch-size:50}") int batchSize) {
        this.outboxRepository = outboxRepository;
        this.eventPublisher = eventPublisher;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${hub.relay.poll-interval-ms:5000}")
    @Transactional
    public void relay() {
        List<OutboxEvent> pending =
                outboxRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING, PageRequest.of(0, batchSize));
        if (pending.isEmpty()) {
            return;
        }

        try {
            eventPublisher.publishBatch(topic, pending);
        } catch (Exception e) {
            // Kafka transaction rolled back — nothing was committed to the broker.
            // Rows stay PENDING and the next poll retries them.
            log.warn("Outbox relay: Kafka publish failed for {} event(s); will retry on next poll", pending.size(), e);
            return;
        }

        pending.forEach(OutboxEvent::markPublished);
        outboxRepository.saveAll(pending);
        log.info("Outbox relay: published {} event(s) to topic '{}'", pending.size(), topic);
    }
}
