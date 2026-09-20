package com.bellick.hub.profile;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * profile-service — "core system" simulation (M2).
 *
 * <p>Consumes {@code customer.profile.events} as an independent consumer
 * group. Processing is idempotent (eventId dedup), failures retry with
 * exponential backoff and land on the DLQ after max attempts; the DLQ has a
 * replay endpoint (design.md §6.2/§10).
 */
@SpringBootApplication
@EnableKafka
public class ProfileServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProfileServiceApplication.class, args);
    }
}
