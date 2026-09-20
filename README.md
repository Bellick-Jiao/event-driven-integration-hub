# Event-Driven Integration Hub

A **Spring Boot 3 + Kafka** integration hub POC that demonstrates the reliability patterns used in real-world banking integrations: **transactional outbox**, **idempotent consumers**, **dead-letter queue with replay**, and **event fan-out** to multiple downstream systems.

> Personal learning project — a miniature of the "Customer 360" integration scenarios found in modern banking: one channel-facing API, one canonical event stream, many downstream consumers (core systems, data platforms), all delivered without loss, without duplication, and replayable.

## Why this project exists

This POC was built to practice and demonstrate the skills required for backend / integration developer roles — in particular:
- Designing and developing **Java + Spring Boot** services with REST APIs and **integration patterns**
- **Kafka** event streaming (producers, consumer groups, retries, DLQ)
- Production-grade concerns: **observability**, **security**, **testing**, **CI/CD**, **Kubernetes/OpenShift-ready** containerization

## High-level architecture

```
Banker Channel / API Clients
        │  POST /api/v1/customers (OAuth2 JWT)
        ▼
integration-api (Spring Boot, :8080)
  REST · JWT Security · Idempotency · Transactional Outbox ──▶ Postgres
        │  Outbox Relay (transactional producer)
        ▼
Kafka (KRaft) ── topic: customer.profile.events (compacted, keyed by customer)
        │                                  │
        ▼                                  ▼
profile-service (:8081)              data-loader (:8082)
  idempotent consumer                   independent consumer group (fan-out)
  retry/backoff → DLQ + replay          event-carried state transfer
        │                                  │
        ▼                                  ▼
  core-system store                   data-platform warehouse
```

See [docs/architecture-diagram.html](docs/architecture-diagram.html) for the full diagram, and [docs/design.md](docs/design.md) for the complete design.

## Tech stack

| Area | Choice |
| --- | --- |
| Language / Framework | Java 21 LTS · Spring Boot 3.5 · Maven (multi-module) |
| Messaging | Apache Kafka 4.x (KRaft, no ZooKeeper) |
| Persistence | PostgreSQL 16 · Flyway migrations |
| Security | Spring Security · OAuth2 Resource Server (JWT via Keycloak) |
| API | springdoc-openapi 3 · ProblemDetail (RFC 9457) |
| Observability | JSON structured logs · Micrometer + Prometheus · Micrometer Tracing + Zipkin |
| Testing | JUnit 5 · AssertJ · **Testcontainers** (Postgres + Kafka) |
| Containers / Deploy | Multi-stage non-root images (OpenShift-friendly) · k8s manifests · GitHub Actions |

## Repo layout

```
services/integration-api   Channel-facing REST API + transactional outbox  (:8080)
services/profile-service   "Core system" consumer: idempotency, retry, DLQ (:8081)
services/data-loader       "Data platform" consumer: event-carried state   (:8082)
docker-compose.yml         Infra: Postgres + Kafka (+ optional Keycloak/Zipkin/Prometheus)
k8s/                       Kubernetes manifests (Deployment/Service/ConfigMap/Secret/HPA)
.github/workflows/         CI: build → Testcontainers tests → GHCR images → kind smoke test
docs/                      design.md · decisions.md · resume.md · architecture diagram
```

## Quick start

Prerequisites: Docker Desktop, JDK 21. (Maven is not required — the repo ships a Maven Wrapper.)

```bash
git clone <your-repo-url> && cd event-driven-integration-hub
cp .env.example .env
docker compose up -d            # Postgres + Kafka (KRaft)
./scripts/init-kafka.sh         # create topics
cd services && ./mvnw -pl integration-api spring-boot:run   # Windows: .\mvnw.cmd
```

Then call the API (details in `docs/design.md` §7):

```bash
curl -X POST http://localhost:8080/api/v1/customers \
  -H "Idempotency-Key: demo-001" -H "Content-Type: application/json" \
  -d '{"customerId":"CUS-0001","name":"Jane Doe","email":"jane@example.com","phone":"+64 21 000 0000","addresses":[],"kycStatus":"PENDING"}'
```

Watch the event flow end to end: outbox → Kafka → both consumers → both stores. Then break a consumer on purpose and observe **retry → DLQ → replay**.

## Roadmap / status

- [x] M0 — repo scaffold, infra compose, parent POM
- [x] M1 — integration-api REST + JPA + Flyway + OpenAPI + transactional outbox (write side) + Testcontainers tests
- [ ] M2 — outbox relay → Kafka + profile-service (idempotency, retry, DLQ, replay) — *core story*
- [ ] M3 — data-loader fan-out, observability, CI pipeline
- [ ] M4 — Keycloak JWT security, k8s manifests + kind smoke deploy, final polish

## Contributing / license

Personal learning project. Open to feedback and ideas — feel free to open an issue.
