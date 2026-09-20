package com.bellick.hub.api.dto;

import com.bellick.hub.api.model.ProcessingStatus;

import java.util.UUID;

/**
 * 202 Accepted response: the change was durably recorded and handed to the
 * outbox; downstream delivery is asynchronous.
 */
public record CustomerAcceptedResponse(
        String customerId,
        UUID eventId,
        ProcessingStatus status) {
}
