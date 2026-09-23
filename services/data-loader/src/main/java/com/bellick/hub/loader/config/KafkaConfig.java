package com.bellick.hub.loader.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka consumer wiring for the data platform.
 *
 * <p>Failure policy deliberately differs from profile-service: analytics is a
 * best-effort downstream. After {@code FixedBackOff} retries the record is
 * logged and acknowledged (no DLQ) - a poisoned message must not block the
 * whole consumer partition. In production this would emit a metric/alert;
 * here a WARN line with the failing key is enough for the demo.
 */
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // 1 retry, 1s apart; exhausted -> log and ACK (do not loop forever).
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (record, ex) -> log.warn("data-loader giving up on record key={} offset={} after retries: {}",
                        record.key(), record.offset(), ex.getMessage()),
                new FixedBackOff(1000L, 1));
        factory.setCommonErrorHandler(errorHandler);

        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }
}
