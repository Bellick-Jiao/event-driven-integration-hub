# Key Design Decisions (ADR-lite)

> Records *why* each choice was made. Every decision = problem + options + choice + rationale.

---

## ADR-001 Kafka event stream instead of synchronous REST calls for downstream integration

- **Problem**: customer-profile changes must reach both the core system and the data platform; synchronous calls make the channel-facing API depend on downstream availability.
- **Options**: A. synchronous REST calls; B. message queue / event stream (Kafka); C. database CDC straight to downstream.
- **Choice**: B (Kafka).
- **Rationale**: decouples producers from consumers (a downstream outage does not affect the channel write path), gives natural fan-out (independent consumer groups), allows event replay (a new downstream can catch up on history), and fits the integration-patterns context of this project. CDC (C) pushes event modelling onto the database and is harder to control.

## ADR-002 Transactional outbox to keep "database and message" consistent

- **Problem**: writing the DB first and then publishing a message can crash in between — "DB changed but message never sent", or the reverse.
- **Options**: A. local message table + background poller (outbox); B. Kafka transactional messages; C. accept the inconsistency.
- **Choice**: A.
- **Rationale**: the outbox writes the event inside the same DB transaction as the business change, so they are atomic by construction; simple to implement and maintain across teams. Kafka transactions would require the broker and the business DB to share one transaction domain, which is usually not available in production. Polling trades milliseconds of latency for correctness — perfectly acceptable for customer-profile changes.

## ADR-003 Consumer-side idempotency + at-least-once, not exactly-once

- **Problem**: Kafka's delivery semantics are at-least-once; network hiccups duplicate messages.
- **Options**: A. consumer-side dedup (`processed_events` unique key); B. producer/consumer transactions (exactly-once semantics); C. accept duplicates.
- **Choice**: A (combined with a transactional producer to keep duplicates minimal).
- **Rationale**: exactly-once needs transaction coordination with high complexity and cost; in production the common pattern is "idempotent consumers + at-least-once", which achieves logically exactly-once effects and stays transparent to downstream systems.

## ADR-004 Dead-letter queue + managed replay, not infinite retries

- **Problem**: poison messages (bad format, invalid business data) would retry forever and block the rest of the queue.
- **Options**: A. infinite retries; B. bounded retries then drop; C. bounded retries + DLQ + manual/API replay.
- **Choice**: C.
- **Rationale**: transient failures (network, dependency hiccups) self-heal via exponential backoff; permanent failures go to the DLQ without blocking the main flow and can be replayed after a fix. This is the standard approach for production integration platforms and demonstrates operational thinking.

## ADR-005 Events carry full state (ECST); downstream never calls back

- **Problem**: after consuming an event, does the downstream need to call upstream again for the latest data?
- **Choice**: the event payload carries a complete customer snapshot; consumers upsert directly.
- **Rationale**: data-platform-style consumers must not depend on upstream API availability; self-contained snapshots make replay safe too. Cost: larger payloads — mitigated with a compacted topic and per-customer partitioning.

## ADR-006 Kafka in KRaft single-node mode, no ZooKeeper

- **Problem**: the POC needs the simplest runnable Kafka.
- **Choice**: official Kafka 4.x image, KRaft mode (controller and broker combined).
- **Rationale**: ZooKeeper was removed from Kafka 4.x, so KRaft is the only correct setup; a single node is enough for local development and CI integration tests.

## ADR-007 Integration tests with Testcontainers, not locally-managed dependencies

- **Problem**: CI and local environments differ; tests must not depend on "Kafka/Postgres happens to be installed on my machine".
- **Choice**: Testcontainers boots real Postgres + Kafka containers per test run.
- **Rationale**: tests run against the same components as production, CI needs zero extra setup, and the local machine stays clean. This is a key engineering practice worth showing.

## ADR-008 Images built to the OpenShift/K8s security baseline (non-root, arbitrary UID)

- **Problem**: many sample images run as root; the OpenShift default SCC rejects them.
- **Choice**: multi-stage builds with `USER 10001:0` (group 0 lets OpenShift inject a random UID), no privileges, optional read-only root filesystem.
- **Rationale**: matches OpenShift/K8s deployment requirements; "deploys to OpenShift as-is" is a verifiable claim and a step toward production.

## ADR-009 OpenAPI as the API contract; unified ProblemDetail errors

- **Problem**: multiple services need a consistent contract and error format.
- **Choice**: springdoc-openapi generates an OpenAPI 3 contract + Spring 6 ProblemDetail (RFC 9457).
- **Rationale**: the contract is interactive (Swagger UI) and machine-consumable; a uniform error format makes downstream handling simpler and reflects enterprise-standards thinking.

---

> Candidate extensions: Schema Registry (centralized message contracts), mTLS (service-to-service mutual auth), API gateway in front (rate limiting / audit). These are tracked in `docs/design.md` §17.
