package com.bellick.hub.profile.config;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaListenerErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka consumer wiring (design.md §10).
 *
 * <p>The listener container factory is defined explicitly because the error
 * handling must be deterministic: retries with exponential-style backoff
 * ({@link FixedBackOff}), then the record is published to the DLQ topic by a
 * {@link DeadLetterPublishingRecoverer}. (Relying on Boot's auto-configuration
 * here is fragile — with more than one {@code CommonErrorHandler} bean it
 * silently falls back to the stock handler, which never dead-letters.)
 *
 * <p>The DLQ ledger listener gets its own handler ({@code dlqErrorHandler})
 * that never re-publishes to the DLQ — that would loop forever.
 */
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${hub.kafka.dlq-topic:customer.profile.events.dlq}") String dlqTopic,
            @Value("${hub.kafka.max-attempts:3}") int maxAttempts,
            @Value("${hub.kafka.backoff-interval-ms:1000}") long backoffIntervalMs) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(dlqTopic, record.partition()));
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer,
                new FixedBackOff(backoffIntervalMs, maxAttempts - 1)));

        // DB commit happens inside the listener's @Transactional; the offset is
        // committed after the listener returns (at-least-once + idempotency).
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }

    /**
     * DLQ ledger listener handler: never re-dead-letters (that would create an
     * infinite loop) — it logs and acknowledges the record.
     *
     * <p>{@code @KafkaListener(errorHandler = ...)} requires a
     * {@link KafkaListenerErrorHandler} (return value = "fallback payload",
     * here {@code null} → treated as acknowledged).
     */
    @Bean
    public KafkaListenerErrorHandler dlqErrorHandler() {
        return new KafkaListenerErrorHandler() {
            @Override
            public Object handleError(Message<?> message, ListenerExecutionFailedException exception) {
                log.warn("DLQ ledger listener failed for key {} (acknowledged): {}",
                        message.getHeaders().get(KafkaHeaders.RECEIVED_KEY), exception.getMessage());
                return null;
            }
        };
    }
}
