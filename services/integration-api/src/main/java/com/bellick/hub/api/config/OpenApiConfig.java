package com.bellick.hub.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI contract metadata. The contract itself is generated from the
 * controllers (springdoc) and exposed at /v3/api-docs + /swagger-ui.html.
 *
 * <p>M4: the API is protected by a Keycloak-issued JWT, so the spec declares
 * a Bearer security scheme — Swagger UI gets an "Authorize" button.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI hubOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Event-Driven Integration Hub API")
                        .version("0.1.0")
                        .description("""
                                Channel-facing REST API of the Event-Driven Integration Hub POC.

                                Writes return 202 Accepted with an eventId: the change is stored
                                together with its domain event in the transactional outbox, and
                                delivery to downstream systems happens asynchronously via Kafka.

                                Secured with a Keycloak-issued JWT (Bearer). Write endpoints
                                additionally require the BANKER realm role.
                                """))
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}
