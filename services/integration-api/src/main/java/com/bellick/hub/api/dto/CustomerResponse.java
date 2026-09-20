package com.bellick.hub.api.dto;

import com.bellick.hub.api.model.Address;
import com.bellick.hub.api.model.KycStatus;
import com.bellick.hub.api.model.ProcessingStatus;

import java.time.Instant;
import java.util.List;

/** Full customer state + aggregate processing status (GET /api/v1/customers/{id}). */
public record CustomerResponse(
        String customerId,
        String name,
        String email,
        String phone,
        List<Address> addresses,
        KycStatus kycStatus,
        long version,
        ProcessingStatus processingStatus,
        Instant updatedAt) {
}
