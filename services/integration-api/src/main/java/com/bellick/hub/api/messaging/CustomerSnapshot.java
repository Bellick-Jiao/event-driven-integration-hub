package com.bellick.hub.api.messaging;

import com.bellick.hub.api.model.Address;
import com.bellick.hub.api.model.KycStatus;

import java.time.Instant;
import java.util.List;

/**
 * The customer snapshot carried inside every event (event-carried state
 * transfer): downstream systems get the full current state and never need
 * to call back into the API.
 */
public record CustomerSnapshot(
        String customerId,
        String name,
        String email,
        String phone,
        List<Address> addresses,
        KycStatus kycStatus,
        long version,
        Instant updatedAt) {
}
