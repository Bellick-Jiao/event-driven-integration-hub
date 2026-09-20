package com.bellick.hub.api.controller;

import com.bellick.hub.api.dto.CustomerAcceptedResponse;
import com.bellick.hub.api.dto.CustomerEventResponse;
import com.bellick.hub.api.dto.CustomerRequest;
import com.bellick.hub.api.dto.CustomerResponse;
import com.bellick.hub.api.dto.CustomerUpdateRequest;
import com.bellick.hub.api.service.CustomerService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Channel-facing API (see docs/design.md §7).
 *
 * <p>Writes return <b>202 Accepted</b> + {@code Location} + {@code eventId}:
 * the change is durably stored and the outbox owns delivery from here on.
 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    public ResponseEntity<CustomerAcceptedResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody CustomerRequest request) {
        CustomerAcceptedResponse response = customerService.create(request, idempotencyKey);
        return ResponseEntity
                .accepted()
                .location(URI.create("/api/v1/customers/" + response.customerId()))
                .body(response);
    }

    @PatchMapping("/{customerId}")
    public ResponseEntity<CustomerAcceptedResponse> update(
            @PathVariable String customerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CustomerUpdateRequest patch) {
        CustomerAcceptedResponse response = customerService.update(customerId, patch, idempotencyKey);
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/{customerId}")
    public ResponseEntity<CustomerResponse> get(@PathVariable String customerId) {
        return ResponseEntity.ok(customerService.get(customerId));
    }

    @GetMapping("/{customerId}/events")
    public ResponseEntity<List<CustomerEventResponse>> events(@PathVariable String customerId) {
        return ResponseEntity.ok(customerService.events(customerId));
    }
}
