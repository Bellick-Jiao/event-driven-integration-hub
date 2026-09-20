package com.bellick.hub.api;

import com.bellick.hub.api.config.OpenApiConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * integration-api — channel-facing REST API + transactional outbox.
 *
 * <p>M1 scope: create/update/query customer profiles, write domain events
 * into the outbox table in the SAME transaction (the transactional outbox
 * write side).
 * <p>M2 scope: {@code OutboxRelay} publishes PENDING outbox rows to Kafka
 * with a transactional producer; profile-service consumes them (idempotent,
 * retry + DLQ).
 */
@SpringBootApplication
@EnableScheduling
@Import(OpenApiConfig.class)
public class ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
