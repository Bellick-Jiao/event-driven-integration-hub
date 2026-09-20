package com.bellick.hub.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI contract metadata. The contract itself is generated from the
 * controllers (springdoc) and exposed at /v3/api-docs + /swagger-ui.html.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI hubOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Event-Driven Integration Hub API")
                .version("0.1.0")
                .description("""
                        Channel-facing REST API of the Event-Driven Integration Hub POC.

                        Writes return 202 Accepted with an eventId: the change is stored
                        together with its domain event in the transactional outbox, and
                        delivery to downstream systems happens asynchronously via Kafka.
                        """));
    }
}
