package com.bellick.hub.api.web;

/**
 * Thrown on conflicts: duplicate customerId, or an Idempotency-Key reused
 * with a different request body. Mapped to 409 ProblemDetail.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
