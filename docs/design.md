# Event-Driven Integration Hub — POC Design Document

> A Spring Boot integration POC in the shape of a bank-style **event-driven
> customer-360 integration hub** — built to practise and demonstrate the core
> skills of backend / integration developer roles.
> Repository positioning: personal learning + portfolio project. **Not** an
> official project of any bank or organisation, and it uses no internal
> information.

---

## 1. Why this project exists (mapping to role skill requirements)

Typical backend / integration developer skill requirements:

| Skill requirement | How this POC addresses it |
| --- | --- |
| Design and develop services in Java + Spring Boot, including REST APIs | `integration-api` provides a complete REST API (OpenAPI 3 docs, RFC 9457 error bodies) |
| Integration patterns | Transactional Outbox, Idempotent Consumer, Dead-Letter Queue (DLQ) + replay, Fan-out, Event-Carried State Transfer (ECST) |
| Secure service-to-service communication (auth, certificates, API protection) | OAuth2/OIDC (Keycloak JWKS validation) + role-based authorization; PII masking in logs; documented mTLS extension path |
| Observability | Structured JSON logs (traceId/eventId), Micrometer + Prometheus metrics, Zipkin distributed tracing, health checks and readiness/liveness probes |
| Quality, maintainability, performance, security | Testcontainers integration tests, multi-module Maven, input validation, non-root containers (OpenShift/K8s security baseline) |
| Nice-to-have: CI/CD, DevOps, Jenkins, AWS, Kubernetes, API gateway | GitHub Actions (build → test → images → kind-cluster smoke); k8s manifests; OpenShift-compatible images (non-root, arbitrary UID); documented API gateway integration path |
| Nice-to-have: Kafka | The whole message bus is Kafka (KRaft mode): producers, consumer groups, partition ordering, retries, DLQ |

**One-line positioning**: a bank-style **event-driven customer-360 integration
hub** — channels submit customer-profile changes over REST, the integration
layer fans them out asynchronously over Kafka to the "core system" and "data
platform", demonstrating the production-grade reliability patterns end to end.
This is the typical shape of Customer360 projects that "connect front-ends,
core systems, data platforms and channels", and its business value can be
summarised in one sentence.

---

## 2. Business scenario (fictional, not real)

- **Scenario**: a bank's customer manager (Banker) updates customer profiles
  through a channel portal (change phone number, add an address, update KYC
  status).
- **Problem**: one piece of customer data must land in the core system *and*
  the customer data platform (analytics/reporting), and may also trigger
  notifications. Synchronous calls create tight coupling, single points of
  failure, and end-to-end latency whenever a downstream is slow; the data also
  has to be delivered *without loss, without duplication, and replayable*.
- **POC approach**: the integration layer decouples with a **transactional
  outbox + Kafka event stream** — the API writes customer data and the outbox
  event in one transaction, a background relay publishes to Kafka, and two
  downstream consumers process independently (fan-out) with idempotency,
  retries and a DLQ for reliable delivery.

---

## 3. Overall architecture

See `docs/architecture-diagram.html` for the architecture diagram (open in a browser).

```
                    ┌─────────────────────────────────────────────────────┐
   Banker Portal ──▶│  integration-api  (Spring Boot 3, :8080)           │
   API Clients  ──▶│  REST API · JWT Security · Idempotency · Outbox     │
   (OAuth2 JWT)    │  Postgres: customer + outbox + idempotency_keys     │
                    └───────────────┬─────────────────────────────────────┘
                                    │ ① write customer + outbox in one TX
                                    ▼
                             Outbox Relay (polling)
                                    │ ② transactional producer publishes
                                    ▼
                    ┌─────────────────────────────────────────────────────┐
                    │  Kafka (single-node KRaft)                          │
                    │  topic: customer.profile.events (compact, keyed by  │
                    │         customer)                                   │
                    │  topic: customer.profile.events.dlq                 │
                    └───────┬───────────────────────────────┬─────────────┘
                            ▼                               ▼
              profile-service (:8081)              data-loader (:8082)
              "core system" simulator              "data platform" simulator
              idempotent consume → update profile   event-carried state → wide table
              retry/backoff → DLQ write + replay API independent consumer group (fan-out)
                            │                               │
                            ▼                               ▼
              Postgres: profile_store          Postgres: data_warehouse
```

**Cross-cutting capabilities**: Keycloak (OIDC/JWT issuance), Prometheus
(metrics), Zipkin (tracing), GitHub Actions (CI/CD), Kubernetes/OpenShift
manifests.

---

## 4. Tech stack

| Component | Choice | Rationale |
| --- | --- | --- |
| Language / framework | Java 21 LTS + Spring Boot 3.5.x | Mainstream enterprise versions (including NZ banking); the role calls for Java + Spring Boot. Note: Spring Boot 4.x shipped in late 2026; this project deliberately stays on 3.x to mirror current production reality, with a 4.x upgrade as a natural next step |
| Build | Maven (multi-module parent + 3 child modules) | One `mvn verify` builds everything; keeps CI simple and the structure clear |
| Messaging | Apache Kafka 4.x (KRaft-only, no ZooKeeper) | Kafka is a core focus of this project; KRaft is the current standard with no extra components |
| Database | PostgreSQL 16 | Fits bank technology stacks; business table / outbox / idempotency tables share one DB as separate tables |
| Security | Spring Security + OAuth2 Resource Server (JWT, JWKS from Keycloak 26) | Demonstrates how real enterprises protect APIs |
| API docs | springdoc-openapi 2.x (Swagger UI + `/v3/api-docs`) | API-contract mindset |
| Error bodies | Spring 6 ProblemDetail (RFC 9457) | Zero extra dependencies, uniform spec |
| Observability | logstash-logback-encoder (JSON logs) + Micrometer + Prometheus + Micrometer Tracing (Brave) + Zipkin | Covers the logs / metrics / tracing trio |
| Testing | JUnit 5 + AssertJ + **Testcontainers** (Postgres + Kafka) | Runs real integration tests without depending on a local environment |
| Containerization | Docker multi-stage builds, non-root (UID 10001) | OpenShift security baseline (runs with an injected random UID) |
| CI/CD | GitHub Actions: build → verify → push images to GHCR → kind-cluster smoke | Free, visible, publicly verifiable |
| Deploy manifests | Kubernetes manifests (Deployment/Service/ConfigMap/Secret/HPA/probes) + OpenShift compatibility notes | Meets OpenShift/K8s deployment requirements; OpenShift cannot run locally, so kind + compatible images stand in |

> Version note: versions above are the mainstream stable releases as of
> mid-2026. At implementation time, pin to the latest stable tags on Maven
> Central / Docker Hub; dependency versions are centralised in the parent
> `pom.xml` (see README).

---

## 5. Repository layout

```
event-driven-integration-hub/
├── README.md                    # GitHub landing page (English)
├── .gitignore
├── .env.example                 # local env template (real secrets never committed)
├── docker-compose.yml           # infrastructure: postgres + kafka (+ optional security/observability profiles)
├── scripts/
│   ├── local-dev.sh             # one-shot: start deps, create topics, run services
│   └── init-kafka.sh            # create topics (compact / dlq / partition count)
├── services/
│   ├── pom.xml                  # parent POM (centralised dependency versions)
│   ├── integration-api/         # channel entry: REST + outbox (:8080)
│   ├── profile-service/         # downstream ①: core-system simulator (:8081)
│   └── data-loader/             # downstream ②: data-platform simulator (:8082)
├── k8s/                         # Deployment/Service/ConfigMap/Secret/HPA manifests
├── docs/
│   ├── design.md                # this document
│   ├── decisions.md             # key design decisions (ADR-lite)
│   ├── k8s-smoke-fixes.md       # kind smoke-deploy troubleshooting notes
│   └── architecture-diagram.html# architecture diagram
└── .github/workflows/
    ├── ci.yml                   # build + test + images + kind smoke
    └── release.yml              # tag-triggered image release (optional)
```

---

## 6. Service responsibilities and package layout

All three services are independently deployable Spring Boot applications
sharing the parent POM. Suggested package layout (integration-api as the
example; the others are analogous):

```
com.bellick.hub.api
├── ApiApplication.java
├── config/        # SecurityConfig, KafkaConfig, JacksonConfig
├── controller/    # CustomerController, DlqController (in profile-service)
├── service/       # CustomerService, OutboxRelay, IdempotencyService
├── messaging/     # EventPublisher (transactional producer), Envelope model
├── repository/    # JPA repositories
├── model/         # entities + event envelope
└── web/           # ProblemDetail handling, OpenAPI configuration
```

### 6.1 integration-api (port 8080) — channel entry
- REST: create/update customer profiles, query processing status, event history, idempotent replay.
- Security: OAuth2 Resource Server (JWT validation; writes require `ROLE_BANKER`).
- Core logic: **one transaction** writes the `customer` table + the `outbox`
  table; `OutboxRelay` polls the outbox and publishes with a **transactional
  Kafka producer** (`enable.idempotence` + transactions on).
- Idempotency: `Idempotency-Key` header → `idempotency_keys` table; a repeated
  request returns the first response.

### 6.2 profile-service (port 8081) — "core system" simulator
- Consumes `customer.profile.events` (independent consumer group; partitioned
  by `customerId` for per-customer ordering).
- **Idempotent consume**: `processed_events` table (eventId unique); duplicate
  events are ACKed and skipped.
- Retry: `DefaultErrorHandler` + exponential backoff; after the max attempts the
  event goes to the **DLQ** topic + `dlq_events` table.
- Admin endpoints: `GET /admin/dlq`, `POST /admin/dlq/{eventId}/replay`
  (re-publishes the event to the main topic — "fix then replay").
- On success, upserts the latest customer profile into the `profile_store`
  table (simulating the core system).

### 6.3 data-loader (port 8082) — "data platform" simulator
- Consumes the same topic with **another consumer group**, demonstrating one-to-many fan-out.
- Event-carried state transfer: the payload contains the full customer
  snapshot and is upserted directly into the `data_warehouse` wide table
  (simulating a warehouse/lake load).

---

## 7. API design (integration-api)

| Method | Path | Description | Success code |
| --- | --- | --- | --- |
| POST | `/api/v1/customers` | Create a customer profile; requires the `Idempotency-Key` header | 202 Accepted (async) |
| PATCH | `/api/v1/customers/{customerId}` | Partial update (phone/address/KYC) | 202 Accepted |
| GET | `/api/v1/customers/{customerId}` | Current state + processing status (PENDING/PROCESSED/FAILED) | 200 |
| GET | `/api/v1/customers/{customerId}/events` | Event history (outbox audit, usable for replay checks) | 200 |
| GET | `/actuator/health` `/actuator/prometheus` | Health check / metrics | 200 |
| GET | `/v3/api-docs` `/swagger-ui.html` | OpenAPI contract / UI | 200 |

Request/response example (the point is **202 + Location + eventId** — the
"asynchronous integration" mindset):

```http
POST /api/v1/customers HTTP/1.1
Authorization: Bearer <jwt>
Idempotency-Key: 7f9c...-uuid
Content-Type: application/json

{
  "customerId": "CUS-0001",
  "name": "Jane Doe",
  "email": "jane@example.com",
  "phone": "+64 21 000 0000",
  "addresses": [{"type": "HOME", "line1": "1 Queen St", "city": "Auckland", "postcode": "1010"}],
  "kycStatus": "VERIFIED"
}
```

```json
HTTP/1.1 202 Accepted
Location: /api/v1/customers/CUS-0001
{
  "customerId": "CUS-0001",
  "eventId": "evt_8f2a...",
  "status": "PENDING"
}
```

Errors are uniform ProblemDetail (RFC 9457): `400` validation failure,
`401/403` unauthenticated/forbidden, `409` idempotency-key conflict, `422`
business validation, `500` internal error.

---

## 8. API ecosystem (API gateway)

The repository demonstrates the **contract → gateway → service → event**
path of a real-world API ecosystem. The gateway itself is **documented, not
deployed**: keeping the demo stack small lets the local run and the kind
smoke stay fast, while the design below shows production posture.

### 8.1 Contract-first

- Every endpoint ships an OpenAPI 3 contract (`/v3/api-docs` + Swagger UI);
  the contract is the single source of truth for consumers.
- Errors are uniform ProblemDetail (RFC 9457), so client and gateway logic
  (retry, alerting) stay simple and predictable.

### 8.2 Gateway responsibilities (production proposal: Kong, DB-less)

In production the gateway sits between the channel clients and
`integration-api` and owns cross-cutting concerns the service should not:

| Concern | How | Why it matters here |
| --- | --- | --- |
| Routing | host/path → `integration-api` upstream | one entry point; services stay hidden behind the gateway |
| Rate limiting | per-client (API key / JWT subject) quotas | protects the API and the downstream Kafka load under burst |
| Audit | access log per request (client, path, status, latency) | banking-grade traceability of who called what |
| AuthN/AuthZ fronting | validate JWT at the edge; pass validated identity downstream | defense in depth; the service still validates (belt and braces) |
| TLS termination | HTTPS to clients, HTTP inside the cluster | certificates live at the edge |

### 8.3 DB-less Kong reference (what the config would look like)

```yaml
# kong.yaml (DB-less): declarative gateway config
_format_version: "2.1"
services:
  - name: integration-api
    url: http://integration-api:8080
    routes:
      - name: customers
        paths: [/api/v1/customers]
        strip_path: false
    plugins:
      - name: rate-limiting
        config: { minute: 60, policy: local }
      - name: correlation-id
        config: { header_name: X-Correlation-ID, generator: uuid#counter }
      - name: key-auth           # or openid-connect for JWT validation
        config: { key_names: [X-API-Key], hide_credentials: true }
```

### 8.4 Where it fits in this POC

- **Local / kind demo**: clients call `integration-api` directly (no gateway
  in docker-compose or the k8s manifests) — the demo stays one command.
- **Interview talking point**: "OpenAPI defines the contract; in production an
  API gateway adds rate limiting, audit and routing in front of the service;
  the service stays focused on business logic and Kafka integration."
- Running it (DB-less Kong in a compose profile + a smoke request) is the
  cheapest next milestone; see §18 Extension ideas.

---

## 9. Data model

**integration-api database** (one DB, multiple tables — no need to split
databases for a POC):

```sql
customer(id BIGSERIAL PK, customer_id VARCHAR UNIQUE, name, email, phone,
         kyc_status, version INT, created_at, updated_at)
outbox(id BIGSERIAL PK, event_id UUID UNIQUE, aggregate_id VARCHAR, type VARCHAR,
       payload JSONB, status VARCHAR,             -- PENDING / PUBLISHED
       created_at, published_at)
idempotency_keys(idempotency_key VARCHAR PK, request_hash VARCHAR,
                 response_body JSONB, created_at)
```

**profile-service database**: `profile_store(customer_id PK, snapshot JSONB,
version INT, updated_at)`; `processed_events(event_id PK, customer_id,
processed_at)`; `dlq_events(event_id PK, topic, payload JSONB, reason,
retried_at, status)`.

**data-loader database**: `data_warehouse(customer_id PK, snapshot JSONB,
loaded_at)`.

> The POC manages schema changes with JPA + Flyway (database changes are
> version-controlled).

---

## 10. Kafka design

| Item | Design |
| --- | --- |
| Broker | Local single-node KRaft (no ZooKeeper), started with one docker-compose command |
| Main topic | `customer.profile.events`, `partitions=3`, `cleanup.policy=compact` (keeps each customer's latest state; supports replay / new-consumer catch-up) |
| DLQ topic | `customer.profile.events.dlq` |
| Partition key | `customerId` → events for one customer stay ordered |
| Delivery semantics | Transactional producer + `enable.idempotence=true`; consumer `enable.auto.commit=false` + manual ACK; overall **at-least-once**, combined with consumer-side idempotency → logically exactly-once |
| Event envelope | `{eventId, type, schemaVersion, occurredAt, customerId, sourceChannel, traceId, payload}`; schemaVersion starts at 1; payload is the full customer snapshot (ECST) |

Event types: `CustomerCreated` / `CustomerUpdated` / `AddressChanged`. Field
evolution: bump `schemaVersion` to 2 while keeping backward compatibility
(consumers branch on version) — a good hook for the "message contract
evolution" topic.

Topic creation script (`scripts/init-kafka.sh`):

```bash
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic customer.profile.events \
  --partitions 3 --replication-factor 1 --config cleanup.policy=compact
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic customer.profile.events.dlq --partitions 3 --replication-factor 1
```

---

## 11. Integration pattern catalogue

| Pattern | Where implemented | Problem solved | One-line explanation |
| --- | --- | --- | --- |
| Transactional outbox | integration-api: customer + outbox in one TX, relay publishes | Dual-write inconsistency (DB committed but message never sent) | "The outbox makes the DB write and the message atomic, avoiding distributed transactions" |
| Idempotent consumer | profile-service: `processed_events` unique key | Duplicate messages under at-least-once delivery | "The consumer dedupes by event ID; redelivery has no side effects" |
| DLQ + replay | Retries exhausted → DLQ; `/admin/dlq/{id}/replay` | Poison messages block consumption | "Unprocessable messages go to the DLQ and can be replayed in one click — production-operable" |
| Fan-out (Pub/Sub) | Two independent consumer groups on the same topic | One change must reach multiple downstreams | "Kafka delivers one change to the core system and the data platform without blocking either" |
| Event-carried state transfer | data-loader: payload is the full snapshot, written straight to a wide table | Downstream must not call back upstream | "The event is self-contained; the data platform never calls back" |
| Retry + backoff | DefaultErrorHandler + exponential backoff | Transient failures self-heal | "Transient failures retry with exponential backoff instead of hammering the downstream" |
| Message contract versioning | `schemaVersion` in the envelope | Evolution without breaking consumers | "Events carry a version; compatible evolution" |
| Secure service communication | JWT/OAuth2 + documented mTLS extension | API protection | "The API is OIDC-protected; service-to-service can be upgraded to mTLS" |

---

## 12. Observability design

- **Structured logs**: logstash-logback-encoder emits JSON; MDC injects
  `traceId / eventId / customerId / service`; **PII masking** (emails and
  phone numbers masked in logs — a banking-sector convention).
- **Metrics**: custom Micrometer counters/timers — `hub.events.published`,
  `hub.events.consumed`, `hub.events.dlq`, `hub.retries`, consumption lag —
  alongside Kafka client metrics.
- **Tracing**: Micrometer Tracing + Brave; `traceId` propagates through Kafka
  message headers; Zipkin shows the end-to-end span (POST → publish → consume
  → persist).
- **Health checks**: liveness/readiness probes (including Kafka and DB
  dependency checks) — used directly by the k8s manifests.

---

## 13. Security design

- **API protection**: integration-api is an OAuth2 Resource Server validating
  JWTs against Keycloak's JWKS; writes require `ROLE_BANKER`, reads also
  require authentication (least privilege).
- **Secret management**: `.env` locally (never committed), k8s Secret; no
  hard-coded credentials in containers.
- **Logs and responses**: PII masking; error messages do not leak internals.
- **Extensions (documented only)**: service-to-service mTLS (self-signed CA
  script), Kafka SASL/SSL, an API gateway (Apisix/Kong/Spring Cloud Gateway)
  in front for rate limiting and audit — showing "I know what production
  needs, and here is the path".

---

## 14. Testing strategy

| Level | Scope | Tooling |
| --- | --- | --- |
| Unit tests | Outbox relay state machine, idempotency logic, envelope serialization | JUnit 5 + Mockito |
| Integration tests | **Testcontainers boots Postgres + Kafka**: ① POST → outbox → publish → consume → persist, end to end; ② consumer throws → retry → DLQ → replay recovery; ③ duplicate events ignored idempotently; ④ fan-out reaches both consumer groups | Testcontainers |
| Contract / docs | OpenAPI file as contract; interactive Swagger UI | springdoc |
| CI smoke | kind cluster deploys all three services → curl health → one POST proves the end-to-end path | GitHub Actions |

> Testcontainers keeps integration tests independent of any external
> environment, so they run stably in CI too.

---

## 15. CI/CD and containerization

**GitHub Actions `ci.yml` pipeline (three stages)**:

```
build:    JDK 21 + Maven → mvn verify (incl. Testcontainers integration tests) → upload test reports
docker:   (main/tag) buildx multi-platform build → push to GHCR (integration-api / profile-service / data-loader)
smoke:    (main) kind cluster → apply k8s manifests → wait Ready → curl health → print result
```

**Image spec (OpenShift-compatible)**:
- Multi-stage build: `eclipse-temurin:21-jdk` to compile → JRE runtime layer;
- non-root user (`UID 10001`), `USER 10001:0` (group 0 allows OpenShift's
  random UID mapping);
- minimal layers; `ENTRYPOINT` in exec form.

**k8s manifest highlights (example in the appendix)**: Deployment (replicas=2,
probes, resource limits), Service, ConfigMap, Secret, HPA, OpenShift Route
notes. In production Kafka would be managed by the Strimzi Operator (locally
docker-compose suffices — documented).

---

## 16. Local run guide

```bash
# 0. Prerequisites: Docker Desktop (Windows) + JDK 21 + Maven
git clone <your-repo-url> && cd event-driven-integration-hub
cp .env.example .env

# 1. Start infrastructure (Postgres + Kafka; optional --profile security adds
#    Keycloak, --profile observability adds Zipkin/Prometheus)
docker compose up -d
./scripts/init-kafka.sh        # create topics

# 2. Run the services (option A: local JVM)
cd services && mvn -pl integration-api spring-boot:run   # likewise profile-service / data-loader

# 3. End-to-end verification
curl -X POST http://localhost:8080/api/v1/customers \
  -H "Idempotency-Key: demo-001" -H "Content-Type: application/json" \
  -d '{"customerId":"CUS-0001","name":"Jane Doe","email":"jane@example.com","phone":"+64 21 000 0000","addresses":[],"kycStatus":"PENDING"}'
# Observe: Kafka topic consumption, profile_store upsert, data_warehouse upsert
# Trigger a failure: send a payload the consumer always fails on → observe retry → DLQ → /admin/dlq/{id}/replay
```

---

## 17. Milestone roadmap (each stage ships something demonstrable)

| Stage | Content | Practice / deliverables | Suggested effort |
| --- | --- | --- | --- |
| **M0** | Repo scaffold: README, .gitignore, docker-compose (postgres+kafka), parent POM | Git workflow, Docker Compose | half a day |
| **M1** | integration-api: REST CRUD + JPA + Flyway + OpenAPI + health checks | Spring Boot basics, Spring Data JPA | 1–2 weekends |
| **M2** | Outbox + Kafka producer; profile-service consumer with idempotency, retry, DLQ, replay | **Kafka core** (producer/consumer/partition/retry/DLQ) — the project's core story | 1–2 weekends |
| **M3** | data-loader fan-out; observability trio; Testcontainers full-chain tests; CI pipeline | Micrometer/tracing/JSON logs; **CI/CD**; testing engineering | 1–2 weekends |
| **M4** | Keycloak JWT protection; k8s manifests + kind smoke; README final polish | **OpenShift/K8s deployment concepts**; security | 1 weekend |

> M1–M4 are all complete and continuously verified in CI (build, tests,
> images, kind smoke).

---

## 18. Extension ideas (after the main line, by value-for-effort)

1. **Schema Registry** (Apicurio/Confluent): centralised message-contract
   management + compatibility checks — directly matches the "integration
   platform" mindset.
2. **mTLS service-to-service communication**: self-signed CA script, showing
   certificates and mutual auth (the role mentions certificates).
3. **API gateway in front** (Kong / Apisix / Spring Cloud Gateway): rate
   limiting, audit, routing — maps to "API gateway / API management".
   The design is documented in [§8](#8-api-ecosystem-api-gateway); running it
   (DB-less Kong in a compose profile + a smoke request) is a half-day
   milestone.
4. **Kafka SASL/SSL**: upgrade from plaintext to authenticated, encrypted
   transport — deeper security.
5. **Contract tests with Pact**: consumer-driven contracts, verifying
   provider/consumer evolution.
6. **Spring Boot 4.x upgrade**: show version-migration capability.
7. **Grafana dashboard JSON**: a finished metric-visualization artifact with
   a README screenshot.
8. **Deploy Kafka with Strimzi on kind**: also covers "Kafka on K8s".

---

## Appendix A: k8s manifest example (integration-api)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: integration-api
spec:
  replicas: 2
  selector: { matchLabels: { app: integration-api } }
  template:
    metadata: { labels: { app: integration-api } }
    spec:
      securityContext:
        runAsNonRoot: true
        seccompProfile: { type: RuntimeDefault }
      containers:
        - name: integration-api
          image: ghcr.io/bellick-jiao/integration-hub/integration-api:latest
          ports: [{ containerPort: 8080 }]
          envFrom: [{ configMapRef: { name: hub-config } }, { secretRef: { name: hub-secrets } }]
          readinessProbe: { httpGet: { path: /actuator/health/readiness, port: 8080 }, initialDelaySeconds: 15 }
          livenessProbe:  { httpGet: { path: /actuator/health/liveness,  port: 8080 }, initialDelaySeconds: 30 }
          resources:
            requests: { cpu: 250m, memory: 512Mi }
            limits:   { cpu: 1,    memory: 1Gi }
---
apiVersion: v1
kind: Service
metadata: { name: integration-api }
spec:
  selector: { app: integration-api }
  ports: [{ port: 80, targetPort: 8080 }]
```

> OpenShift note: images run non-root with an arbitrary UID and need no
> privileged containers, so they pass the restricted SCC by default. In
> production Kafka is best managed by the Strimzi Operator.
