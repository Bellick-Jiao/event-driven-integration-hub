package com.bellick.hub.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * M4 security: OAuth2 Resource Server.
 *
 * <p>The API trusts only JWTs signed by our Keycloak realm (issuer discovered
 * from {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}). Rule set
 * (least privilege):
 * <ul>
 *   <li>write endpoints (POST/PATCH) require the realm role {@code BANKER};</li>
 *   <li>read endpoints require any valid (authenticated) token;</li>
 *   <li>health probes, Prometheus metrics and the OpenAPI/Swagger UI are public.</li>
 * </ul>
 *
 * <p>Authorities are mapped from the Keycloak {@code realm_access.roles} claim
 * ({@code ROLE_<role>}), so {@code hasRole("BANKER")} matches a JWT whose
 * {@code realm_access.roles} contains {@code BANKER}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // stateless bearer-token API; no cookies, no CSRF surface
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/customers", "/api/v1/customers/**").hasRole("BANKER")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/customers/**").hasRole("BANKER")
                        // Probes, metrics and API docs stay reachable without a token.
                        .requestMatchers(
                                "/actuator/health", "/actuator/health/**",
                                "/actuator/prometheus",
                                "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /**
     * Maps JWT claims to Spring authorities:
     * default scope conversion (SCOPE_*) + Keycloak realm roles (ROLE_*).
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter(); // scp → SCOPE_*

        Converter<Jwt, Collection<GrantedAuthority>> realmRoles = jwt -> {
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
                return List.of();
            }
            return roles.stream()
                    .filter(String.class::isInstance)
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
        };

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
            authorities.addAll(realmRoles.convert(jwt));
            return authorities;
        });
        return converter;
    }
}
