-- =====================================================================
-- V1: initial schema for data-loader ("data platform" simulation)
--   data_warehouse  - wide table: latest snapshot per customer (ECST upsert)
--   processed_events- idempotency ledger: one row per consumed eventId
-- Owned by Flyway inside the dedicated "data_loader" schema.
-- JPA runs with ddl-auto=validate.
-- =====================================================================

CREATE TABLE data_warehouse
(
    customer_id VARCHAR(64) PRIMARY KEY,
    snapshot    JSONB       NOT NULL,
    loaded_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE processed_events
(
    event_id     UUID        PRIMARY KEY,
    customer_id  VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
