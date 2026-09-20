package com.bellick.hub.api.messaging;

import com.bellick.hub.api.model.OutboxEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Transactional Kafka publisher used by the outbox relay (M2).
 *
 * <p>All sends of one batch happen inside a single Kafka transaction
 * ({@code executeInTransaction}) with producer idempotence enabled
 * (see application.yml). Either the whole batch is committed to the broker
 * or none of it is — there is no half-published batch.
 *
 * <p>Delivery is at-least-once: the relay marks rows {@code PUBLISHED} only
 * AFTER the Kafka transaction commits, so a crash in between can redeliver —
 * which is exactly why the consumer side is idempotent (design.md §9/§10).
 */
@Component
public class EventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public EventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publishes a batch of outbox rows to {@code topic} in one Kafka
     * transaction. Partition key = {@code aggregateId} (customerId), so all
     * events of one customer land on the same partition and stay ordered.
     */
    public void publishBatch(String topic, List<OutboxEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        kafkaTemplate.executeInTransaction(operations -> {
            for (OutboxEvent event : events) {
                operations.send(topic, event.getAggregateId(), event.getPayload());
            }
            return null;
        });
    }
}
