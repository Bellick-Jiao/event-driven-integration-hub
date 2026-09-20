package com.bellick.hub.api.dto;

import com.bellick.hub.api.model.Address;
import com.bellick.hub.api.model.KycStatus;
import jakarta.validation.Valid;

import java.util.List;

/**
 * Partial update body (PATCH /api/v1/customers/{customerId}).
 * Null fields are ignored; only provided fields are applied.
 */
public record CustomerUpdateRequest(
        @jakarta.validation.constraints.Email(message = "email must be a valid address")
        String email,
        String phone,
        List<@Valid Address> addresses,
        KycStatus kycStatus) {
}
