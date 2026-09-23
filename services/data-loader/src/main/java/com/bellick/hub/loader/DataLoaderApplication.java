package com.bellick.hub.loader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * data-loader (M3): the "data platform" downstream.
 *
 * <p>Consumes {@code customer.profile.events} in its own consumer group,
 * independently of profile-service - one event stream, many downstream
 * systems that do not block each other (fan-out).
 */
@SpringBootApplication
public class DataLoaderApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataLoaderApplication.class, args);
    }
}
