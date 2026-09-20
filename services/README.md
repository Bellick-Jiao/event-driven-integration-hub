# services — Spring Boot multi-module Maven project

## Modules

| Module | Port | Status | Purpose |
| --- | --- | --- | --- |
| `integration-api` | 8080 | **M1 + M2 done** | Channel-facing REST API + transactional outbox (write side) + **outbox relay → Kafka** |
| `profile-service` | 8081 | **M2 done** | "Core system" consumer: idempotency, retry, DLQ, replay |
| `data-loader` | 8082 | M3 (planned) | "Data platform" consumer: event fan-out, event-carried state |

## M1 — what is implemented

- REST API (see `docs/design.md` §7):
  - `POST /api/v1/customers` — create customer, **requires `Idempotency-Key`** → `202 + eventId`
  - `PATCH /api/v1/customers/{customerId}` — partial update → `202 + eventId`
  - `GET /api/v1/customers/{customerId}` — state + aggregate `processingStatus`
  - `GET /api/v1/customers/{customerId}/events` — event history (outbox audit)
  - `GET /actuator/health` (+ liveness/readiness probes), `/v3/api-docs`, `/swagger-ui.html`
- **Transactional outbox (write side)**: `customer` + `outbox` rows commit in the **same DB transaction** — the change and its event can never diverge.
- **Idempotency**: first response per `Idempotency-Key` is stored and replayed; reusing a key with a different body → `409`.
- **Errors**: RFC 9457 `ProblemDetail` everywhere (`400/404/409/500`).
- **Schema**: Flyway-managed (`V1__init.sql`), JPA runs with `ddl-auto=validate`.

## M2 — what is implemented (the core story)

**integration-api (producer side)**
- `OutboxRelay` polls the oldest `PENDING` outbox rows (`@Scheduled`, batching) and publishes them to
  `customer.profile.events` with a **transactional producer** (`executeInTransaction` + producer idempotence).
  Rows are flipped to `PUBLISHED` only after the Kafka transaction commits; failures stay `PENDING` and retry
  on the next poll — no message loss, at-least-once delivery (design.md §9).

**profile-service (consumer side)**
- `ProfileEventConsumer` (`@KafkaListener`, independent group) — **idempotent by `eventId`**
  (`processed_events` table), upserts the customer snapshot into `profile_store` (event-carried state).
- `kafkaErrorHandler` — **exponential-backoff retries**, then publishes to the DLQ topic
  (`customer.profile.events.dlq`); `DlqListener` records each dead letter in the `dlq_events` ledger.
- Admin API:
  - `GET /admin/dlq` — list dead letters
  - `POST /admin/dlq/{eventId}/replay` — republish the original envelope back to the main topic and mark `REPLAYED`

## Run it

Prerequisites: **JDK 21** + **Docker Desktop running** (Postgres + Kafka for M2).

```bash
# 1. Start infra (Postgres + Kafka)
docker compose up -d

# 2. Create topics (compacted main topic + DLQ). On Windows run this from
#    git-bash/WSL, or execute the equivalent kafka-topics.sh commands manually:
./scripts/init-kafka.sh

# 3. Run the services (two terminals) — Maven comes via the wrapper
cd services
.\mvnw.cmd -pl integration-api spring-boot:run    # :8080
.\mvnw.cmd -pl profile-service spring-boot:run    # :8081
```

Smoke test — create a customer, then watch the flow:

```bash
curl -s -X POST http://localhost:8080/api/v1/customers \
  -H "Idempotency-Key: demo-001" -H "Content-Type: application/json" \
  -d '{"customerId":"CUS-0001","name":"Jane Doe","email":"jane@example.com","phone":"+64 21 000 0000","addresses":[],"kycStatus":"PENDING"}'
# → 202 { "customerId":"CUS-0001", "eventId":"...", "status":"PENDING" }

# after ~5s (relay poll), the consumer has persisted the profile:
curl -s http://localhost:8081/admin/dlq                          # empty DLQ
# check integration-api: outbox row flipped to PUBLISHED
curl -s http://localhost:8080/api/v1/customers/CUS-0001/events
```

Break it on purpose: send a poison payload to `customer.profile.events` (e.g. malformed JSON via a Kafka console producer),
watch it retry, land on the DLQ ledger, then `POST /admin/dlq/{eventId}/replay` after "fixing" it.

## Tests

```bash
cd services
.\mvnw.cmd verify            # builds BOTH modules, runs all tests
```

- integration-api: REST contract tests (7) + **outbox relay → Kafka** end-to-end (Testcontainers Postgres + Kafka)
- profile-service: happy path, duplicate-event idempotency, poison → DLQ, replay recovery (Testcontainers)

Integration tests need Docker; they skip automatically when no daemon is available.

## Structure

```
integration-api  (package com.bellick.hub.api)
controller/   REST endpoints            service/    CustomerService, OutboxRelay, IdempotencyService
model/        entities + enums          repository/ Spring Data JPA
messaging/    EventEnvelope, CustomerSnapshot, EventPublisher (transactional producer)
dto/          request/response records  web/       RFC 9457 error contract
config/       OpenAPI metadata

profile-service (package com.bellick.hub.profile)
controller/   DlqController (list + replay)     service/  ProfileEventConsumer, DlqListener
model/        ProfileStore, ProcessedEvent, DlqEvent   repository/ Spring Data JPA
messaging/    Envelope (contract mirror of integration-api's envelope)
config/       KafkaConfig (retry/backoff + DLQ error handlers)
```
