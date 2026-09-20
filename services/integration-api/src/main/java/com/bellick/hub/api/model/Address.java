package com.bellick.hub.api.model;

import jakarta.validation.constraints.NotBlank;

/**
 * A postal address attached to a customer profile.
 * Stored as a JSONB array on the {@code customer} table.
 */
public record Address(
        @NotBlank(message = "address.type is required") String type,
        @NotBlank(message = "address.line1 is required") String line1,
        String city,
        String postcode) {
}
