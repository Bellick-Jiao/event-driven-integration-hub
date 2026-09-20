-- =====================================================================
-- V1: initial schema for integration-api
--   customer            - the business aggregate (customer profile)
--   outbox              - transactional outbox: one row per domain event
--   idempotency_keys    - Idempotency-Key support (repeat requests are safe)
-- Schema is owned by Flyway; JPA runs with ddl-auto=validate.
-- =====================================================================

CREATE TABLE customer
(
    id          BIGSERIAL PRIMARY KEY,
    customer_id VARCHAR(64)  NOT NULL,
    name        VARCHAR(255) NOT NULL,
    email       VARCHAR(255),
    phone       VARCHAR(64),
    kyc_status  VARCHAR(16),
    addresses   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_customer_customer_id UNIQUE (customer_id)
);

CREATE TABLE outbox
(
    id           BIGSERIAL PRIMARY KEY,
    event_id     UUID        NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,          -- the business id (customerId)
    type         VARCHAR(32) NOT NULL,          -- CUSTOMER_CREATED / CUSTOMER_UPDATED
    payload      JSONB       NOT NULL,          -- full event envelope (see EventEnvelope)
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING / PUBLISHED
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    CONSTRAINT uk_outbox_event_id UNIQUE (event_id)
);

-- Index used by the outbox relay (M2): poll oldest PENDING rows in order.
CREATE INDEX ix_outbox_status_created ON outbox (status, created_at);
CREATE INDEX ix_outbox_aggregate      ON outbox (aggregate_id);

CREATE TABLE idempotency_keys
(
    idempotency_key VARCHAR(128) PRIMARY KEY,
    request_hash    VARCHAR(64)  NOT NULL,      -- sha-256 of the normalized request
    response_body   JSONB,                      -- first response, replayed on repeat
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
