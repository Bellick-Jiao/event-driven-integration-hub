package com.bellick.hub.api.model;

/**
 * Aggregate processing status returned by the read API.
 * Derived from the outbox rows of the customer:
 * any PENDING event means the change has not reached the downstream systems yet.
 */
public enum ProcessingStatus {
    PENDING,
    PROCESSED
}
