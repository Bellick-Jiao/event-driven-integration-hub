package com.bellick.hub.api.service;

import com.bellick.hub.api.model.IdempotencyKey;
import com.bellick.hub.api.repository.IdempotencyKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Idempotency-Key ledger: stores the first response per key so repeat
 * requests can be replayed safely (no duplicate side effects).
 *
 * <p>Note on the race: two concurrent requests with the same key could both
 * pass the lookup; the PRIMARY KEY on {@code idempotency_key} is the real
 * guard — one insert wins, the other fails on the constraint. A production
 * implementation typically uses a dedicated REQUIRES_NEW ledger transaction;
 * kept simple here on purpose.
 */
@Service
public class IdempotencyService {

    private final IdempotencyKeyRepository repository;

    public IdempotencyService(IdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public Optional<IdempotencyKey> find(String key) {
        return repository.findById(key);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void store(String key, String requestHash, String responseBodyJson) {
        repository.save(new IdempotencyKey(key, requestHash, responseBodyJson));
    }

    /** sha-256 hex of the normalized request, used to detect key reuse with a different body. */
    public String hash(String normalizedRequest) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalizedRequest.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
