package com.bellick.hub.api.dto;

import com.bellick.hub.api.model.Address;
import com.bellick.hub.api.model.KycStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * Create-customer request body (POST /api/v1/customers).
 */
public record CustomerRequest(
        @NotBlank(message = "customerId is required")
        @Pattern(regexp = "CUS-\\d{4}", message = "customerId must match CUS-XXXX")
        String customerId,

        @NotBlank(message = "name is required")
        String name,

        @Email(message = "email must be a valid address")
        String email,

        String phone,

        List<@Valid Address> addresses,

        KycStatus kycStatus) {
}
