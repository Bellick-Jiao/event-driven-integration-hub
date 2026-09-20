package com.bellick.hub.api.web;

/**
 * Thrown when a requested aggregate does not exist. Mapped to 404 ProblemDetail.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
