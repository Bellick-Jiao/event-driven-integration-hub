-- =====================================================================
-- V1: initial schema for profile-service ("core system" simulation)
--   profile_store   - latest customer snapshot per customer (ECST upsert)
--   processed_events- idempotency ledger: one row per consumed eventId
--   dlq_events      - dead-letter ledger with replay status
-- Schema is owned by Flyway; JPA runs with ddl-auto=validate.
-- =====================================================================

CREATE TABLE profile_store
(
    customer_id VARCHAR(64) PRIMARY KEY,
    snapshot    JSONB       NOT NULL,
    version     BIGINT      NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE processed_events
(
    event_id     UUID        PRIMARY KEY,
    customer_id  VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE dlq_events
(
    event_id   UUID        PRIMARY KEY,
    topic      VARCHAR(128) NOT NULL,
    -- TEXT, not jsonb: a dead letter may be a poison message whose payload is
    -- NOT valid JSON. We keep the raw bytes so replay is verbatim.
    payload    TEXT         NOT NULL,
    reason     VARCHAR(1024),
    status     VARCHAR(16)  NOT NULL DEFAULT 'DLQED',   -- DLQED / REPLAYED
    retried_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
