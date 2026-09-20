package com.bellick.hub.api.service;

import com.bellick.hub.api.dto.CustomerAcceptedResponse;
import com.bellick.hub.api.dto.CustomerEventResponse;
import com.bellick.hub.api.dto.CustomerRequest;
import com.bellick.hub.api.dto.CustomerResponse;
import com.bellick.hub.api.dto.CustomerUpdateRequest;
import com.bellick.hub.api.messaging.CustomerSnapshot;
import com.bellick.hub.api.messaging.EventEnvelope;
import com.bellick.hub.api.model.Customer;
import com.bellick.hub.api.model.EventType;
import com.bellick.hub.api.model.IdempotencyKey;
import com.bellick.hub.api.model.KycStatus;
import com.bellick.hub.api.model.OutboxEvent;
import com.bellick.hub.api.model.OutboxStatus;
import com.bellick.hub.api.model.ProcessingStatus;
import com.bellick.hub.api.repository.CustomerRepository;
import com.bellick.hub.api.repository.OutboxRepository;
import com.bellick.hub.api.web.ConflictException;
import com.bellick.hub.api.web.NotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Customer profile commands + queries.
 *
 * <p>The core reliability story: every business change and its domain event
 * are written in ONE database transaction — {@code customer} + {@code outbox}
 * commit or roll back together. A separate relay (M2) later publishes the
 * outbox rows to Kafka, so we never have the classic "DB updated but the
 * message was lost" failure, without needing a distributed transaction.
 */
@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final OutboxRepository outboxRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public CustomerService(CustomerRepository customerRepository,
                           OutboxRepository outboxRepository,
                           IdempotencyService idempotencyService,
                           ObjectMapper objectMapper) {
        this.customerRepository = customerRepository;
        this.outboxRepository = outboxRepository;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CustomerAcceptedResponse create(CustomerRequest request, String idempotencyKey) {
        // Idempotency: replay the stored first response when the key is reused.
        if (idempotencyKey != null) {
            var existing = idempotencyService.find(idempotencyKey);
            if (existing.isPresent()) {
                assertSameRequest(existing.get(), request);
                return storedResponse(existing.get());
            }
        }

        if (customerRepository.existsByCustomerId(request.customerId())) {
            throw new ConflictException("Customer " + request.customerId() + " already exists");
        }

        Customer customer = new Customer(
                request.customerId(), request.name(), request.email(), request.phone(),
                request.kycStatus() == null ? KycStatus.PENDING : request.kycStatus(),
                request.addresses());

        UUID eventId = UUID.randomUUID();
        OutboxEvent outbox = new OutboxEvent(
                eventId, customer.getCustomerId(), EventType.CUSTOMER_CREATED,
                envelope(customer, eventId, EventType.CUSTOMER_CREATED));

        customerRepository.save(customer);
        outboxRepository.save(outbox);

        CustomerAcceptedResponse response =
                new CustomerAcceptedResponse(customer.getCustomerId(), eventId, ProcessingStatus.PENDING);

        if (idempotencyKey != null) {
            idempotencyService.store(idempotencyKey, idempotencyService.hash(requestKey(request)), toJson(response));
        }
        return response;
    }

    @Transactional
    public CustomerAcceptedResponse update(String customerId, CustomerUpdateRequest patch, String idempotencyKey) {
        Customer customer = customerRepository.findByCustomerId(customerId)
                .orElseThrow(() -> new NotFoundException("Customer " + customerId + " not found"));

        if (idempotencyKey != null) {
            var existing = idempotencyService.find(idempotencyKey);
            if (existing.isPresent()) {
                assertSameRequest(existing.get(), patch);
                return storedResponse(existing.get());
            }
        }

        customer.applyPatch(patch.email(), patch.phone(), patch.kycStatus(), patch.addresses());
        customerRepository.save(customer); // @Version bumps on concurrent writes

        UUID eventId = UUID.randomUUID();
        OutboxEvent outbox = new OutboxEvent(
                eventId, customer.getCustomerId(), EventType.CUSTOMER_UPDATED,
                envelope(customer, eventId, EventType.CUSTOMER_UPDATED));
        outboxRepository.save(outbox);

        CustomerAcceptedResponse response =
                new CustomerAcceptedResponse(customer.getCustomerId(), eventId, ProcessingStatus.PENDING);

        if (idempotencyKey != null) {
            idempotencyService.store(idempotencyKey, idempotencyService.hash(requestKey(patch)), toJson(response));
        }
        return response;
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(String customerId) {
        Customer customer = customerRepository.findByCustomerId(customerId)
                .orElseThrow(() -> new NotFoundException("Customer " + customerId + " not found"));
        ProcessingStatus status = outboxRepository.existsByAggregateIdAndStatus(customerId, OutboxStatus.PENDING)
                ? ProcessingStatus.PENDING
                : ProcessingStatus.PROCESSED;
        return new CustomerResponse(customer.getCustomerId(), customer.getName(), customer.getEmail(),
                customer.getPhone(), customer.getAddresses(), customer.getKycStatus(),
                customer.getVersion(), status, customer.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public List<CustomerEventResponse> events(String customerId) {
        if (!customerRepository.existsByCustomerId(customerId)) {
            throw new NotFoundException("Customer " + customerId + " not found");
        }
        return outboxRepository.findByAggregateIdOrderByCreatedAtAsc(customerId).stream()
                .map(e -> new CustomerEventResponse(e.getEventId(), e.getType(), e.getStatus(), e.getCreatedAt()))
                .toList();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String envelope(Customer customer, UUID eventId, EventType type) {
        CustomerSnapshot snapshot = new CustomerSnapshot(
                customer.getCustomerId(), customer.getName(), customer.getEmail(), customer.getPhone(),
                customer.getAddresses(), customer.getKycStatus(), customer.getVersion(), customer.getUpdatedAt());
        EventEnvelope envelope = new EventEnvelope(
                eventId, type.name(),
                EventEnvelope.CURRENT_SCHEMA_VERSION, Instant.now(),
                customer.getCustomerId(), EventEnvelope.SOURCE_CHANNEL, null,
                objectMapper.valueToTree(snapshot));
        return toJson(envelope);
    }

    private void assertSameRequest(IdempotencyKey existing, Object request) {
        if (!existing.getRequestHash().equals(idempotencyService.hash(requestKey(request)))) {
            throw new ConflictException("Idempotency-Key was already used with a different request body");
        }
    }

    private CustomerAcceptedResponse storedResponse(IdempotencyKey existing) {
        try {
            return objectMapper.readValue(existing.getResponseBody(), CustomerAcceptedResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored idempotency response is corrupt", e);
        }
    }

    /** Canonical string used for the request hash — field order is fixed by the record definition. */
    private String requestKey(Object request) {
        return toJson(request);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Serialization failed", e);
        }
    }
}
