# services — Spring Boot multi-module Maven project

## Modules

| Module | Port | Status | Purpose |
| --- | --- | --- | --- |
| `integration-api` | 8080 | **M1 done** | Channel-facing REST API + transactional outbox (write side) |
| `profile-service` | 8081 | M2 (planned) | "Core system" consumer: idempotency, retry, DLQ, replay |
| `data-loader` | 8082 | M3 (planned) | "Data platform" consumer: event fan-out, event-carried state |

## M1 — what is implemented

- REST API (see `docs/design.md` §7):
  - `POST /api/v1/customers` — create customer, **requires `Idempotency-Key`** → `202 + eventId`
  - `PATCH /api/v1/customers/{customerId}` — partial update → `202 + eventId`
  - `GET /api/v1/customers/{customerId}` — state + aggregate `processingStatus`
  - `GET /api/v1/customers/{customerId}/events` — event history (outbox audit)
  - `GET /actuator/health` (+ liveness/readiness probes), `/v3/api-docs`, `/swagger-ui.html`
- **Transactional outbox (write side)**: `customer` + `outbox` rows commit in the **same DB transaction** — the change and its event can never diverge. The M2 relay will publish outbox rows to Kafka and flip them to `PUBLISHED`.
- **Idempotency**: first response per `Idempotency-Key` is stored and replayed; reusing a key with a different body → `409`.
- **Errors**: RFC 9457 `ProblemDetail` everywhere (`400/404/409/500`).
- **Schema**: Flyway-managed (`V1__init.sql`), JPA runs with `ddl-auto=validate`.
- **Tests**: Testcontainers (real Postgres) end-to-end tests + unit tests. Run requires Docker.

## Run it (M1)

Prerequisites: **JDK 21** + **Docker Desktop running** (only Postgres is needed for M1).

```bash
# 1. Start Postgres (Kafka not needed until M2)
docker compose up -d postgres

# 2. Run the service (Maven comes via the wrapper — no local Maven install needed)
cd services
.\mvnw.cmd -pl integration-api spring-boot:run
```

Smoke test:

```bash
curl -s -X POST http://localhost:8080/api/v1/customers \
  -H "Idempotency-Key: demo-001" -H "Content-Type: application/json" \
  -d '{"customerId":"CUS-0001","name":"Jane Doe","email":"jane@example.com","phone":"+64 21 000 0000","addresses":[],"kycStatus":"PENDING"}'
# → 202 { "customerId":"CUS-0001", "eventId":"...", "status":"PENDING" }

curl -s http://localhost:8080/api/v1/customers/CUS-0001
curl -s http://localhost:8080/api/v1/customers/CUS-0001/events
```

Run all tests (integration tests spin up a throwaway Postgres via Testcontainers):

```bash
cd services
.\mvnw.cmd -pl integration-api verify
```

## Structure (package root `com.bellick.hub.api`)

```
controller/   REST endpoints (design.md §7)
service/      CustomerService (transactional outbox write), IdempotencyService
model/        JPA entities + enums (design.md §8)
repository/   Spring Data JPA repositories
messaging/    EventEnvelope (canonical envelope, design.md §9) + CustomerSnapshot (ECST)
dto/          request/response records
web/          ProblemDetail error contract (RFC 9457)
config/       OpenAPI metadata
```
