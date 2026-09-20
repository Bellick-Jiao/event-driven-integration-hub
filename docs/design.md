# Event-Driven Integration Hub — POC 设计文档

> 面向 **BNZ Integration Developer（Customer360 集成团队）** 申请准备的 Spring Boot 集成型 POC。
> 职位链接：https://nab.eightfold.ai/careers/job/563980770587260（申请截止 2026-09-27）
> 本仓库定位：个人学习 + 简历项目。**不是** BNZ 官方项目，也未使用任何 BNZ 内部信息。

---

## 1. 为什么做这个项目（与职位 JD 的对应关系）

职位 JD 的硬性要求是：

| JD 要求 | 本 POC 如何体现 |
| --- | --- |
| 用 Java + Spring Boot 设计开发服务，含 REST API | `integration-api` 提供完整 REST API（OpenAPI 3 文档、RFC 9457 错误体） |
| 集成模式（integration patterns） | 事务性发件箱（Transactional Outbox）、幂等消费者（Idempotent Consumer）、死信队列（DLQ）+ 重放、事件扇出（Fan-out）、事件携带状态传输（ECST） |
| 安全的服务间通信（认证、证书、API 保护） | OAuth2/OIDC（Keycloak JWKS 校验）+ 基于角色的授权；日志 PII 掩码；文档化 mTLS 扩展方案 |
| 可观测性（observability） | 结构化 JSON 日志（含 traceId/eventId）、Micrometer + Prometheus 指标、Zipkin 分布式追踪、健康检查与就绪/存活探针 |
| 质量、可维护性、性能、安全 | Testcontainers 集成测试、多模块 Maven、输入校验、容器以非 root 用户运行（面向 OpenShift/K8s 安全基线） |
| 加分项：CI/CD、DevOps、Jenkins、AWS、Kubernetes、API 网关 | GitHub Actions（构建→测试→镜像→kind 集群冒烟）；K8s 清单；OpenShift 兼容镜像（非 root、任意 UID）；文档化 API 网关接入方式 |
| 加分项：Kafka（你正在补） | 整个项目的消息总线就是 Kafka（KRaft 模式），覆盖生产者、消费者组、分区顺序、重试、DLQ |

**一句话定位**：一个"银行式"的**事件驱动客户 360 集成中枢**——渠道端通过 REST 提交客户档案变更，集成层用 Kafka 异步扇出到"核心系统"和"数据平台"，并完整演示生产级可靠性模式。这正是 Customer360 这类项目里"连接前端、核心系统、数据平台与渠道"的典型形态，面试时一句话就能讲清楚业务价值。

---

## 2. 业务场景（虚构，非真实）

- **场景**：银行客户经理（Banker）通过渠道门户更新客户档案（改手机号、加地址、更新 KYC 状态）。
- **痛点**：一份客户数据要同时落到核心系统、客户数据平台（供分析/报表用），还可能触发通知。同步调用会造成强耦合、单点故障、下游慢则全链路慢；还需要保证"不丢、不重、可回放"。
- **POC 做法**：集成层用 **事务性发件箱 + Kafka 事件流** 解耦——API 一次事务写入客户数据 + 发件箱事件，后台 relay 发布到 Kafka，两个下游消费者各自独立消费（扇出），配合幂等、重试、DLQ 保证可靠投递。

---

## 3. 总体架构

详见 `docs/architecture-diagram.html`（架构示意图，浏览器打开即可查看）。

```
                    ┌─────────────────────────────────────────────────────┐
   Banker Portal ──▶│  integration-api  (Spring Boot 3, :8080)           │
   API Clients  ──▶│  REST API · JWT Security · Idempotency · Outbox     │
   (OAuth2 JWT)    │  Postgres: customer + outbox + idempotency_keys     │
                    └───────────────┬─────────────────────────────────────┘
                                    │ ① 同一事务写 customer + outbox
                                    ▼
                             Outbox Relay（轮询）
                                    │ ② 事务性生产者发布
                                    ▼
                    ┌─────────────────────────────────────────────────────┐
                    │  Kafka（KRaft 单节点）                                │
                    │  topic: customer.profile.events（compact, 按客户分区） │
                    │  topic: customer.profile.events.dlq                 │
                    └───────┬───────────────────────────────┬─────────────┘
                            ▼                               ▼
              profile-service (:8081)              data-loader (:8082)
              「核心系统」模拟                     「数据平台」模拟
              幂等消费 → 更新 profile 表         事件携带状态 → 写宽表
              重试/退避 → DLQ 写入 + 重放 API    独立消费组（扇出）
                            │                               │
                            ▼                               ▼
              Postgres: profile_store          Postgres: data_warehouse
```

**横切能力**：Keycloak（OIDC/JWT 签发）、Prometheus（指标）、Zipkin（追踪）、GitHub Actions（CI/CD）、Kubernetes/OpenShift 清单部署。

---

## 4. 技术栈

| 组件 | 选型 | 理由 |
| --- | --- | --- |
| 语言 / 框架 | Java 21 LTS + Spring Boot 3.5.x | 企业（含 NZ 银行）主流版本；JD 明写 Java + Spring Boot。注：2026 年底 Spring Boot 4.x 已发布，本项目刻意停在 3.x 以贴近生产现状，升级 4.x 可作为加分扩展 |
| 构建 | Maven（多模块 parent + 3 个子模块） | 一次 `mvn verify` 全量构建，CI 简单；多模块也是简历亮点 |
| 消息 | Apache Kafka 4.x（KRaft-only，无 ZooKeeper） | 你在补 Kafka；KRaft 是当前标准，无需额外组件 |
| 数据库 | PostgreSQL 16 | 与银行技术栈贴合；业务库/发件箱/幂等表同库不同表 |
| 安全 | Spring Security + OAuth2 Resource Server（JWT，JWKS 来自 Keycloak 26） | 演示真实企业的"API 保护"方式 |
| API 文档 | springdoc-openapi 2.x（Swagger UI + `/v3/api-docs`） | 面试展示 API 契约意识 |
| 错误体 | Spring 6 ProblemDetail（RFC 9457） | 零依赖，规范统一 |
| 可观测性 | logstash-logback-encoder（JSON 日志）+ Micrometer + Prometheus + Micrometer Tracing(Brave) + Zipkin | 覆盖日志/指标/追踪三件套 |
| 测试 | JUnit 5 + AssertJ + **Testcontainers**（Postgres + Kafka） | 不依赖外部环境即可跑真实集成测试，是重要简历点 |
| 容器化 | Docker 多阶段构建，非 root（UID 10001）运行 | OpenShift 安全基线（随机 UID 可运行） |
| CI/CD | GitHub Actions：build → verify → 镜像推送 GHCR → kind 集群冒烟 | 免费、可见、面试可直接演示 |
| 部署清单 | Kubernetes manifests（Deployment/Service/ConfigMap/Secret/HPA/探针）+ OpenShift 兼容说明 | 命中 JD 的 Kubernetes 加分项；OpenShift 无法本地直跑，用 kind + 兼容镜像贴近 |

> 版本注意：以上版本号为 2026 年中的主流稳定版本。实际编写时以 Maven Central / Docker Hub 最新稳定 tag 为准，README 中保留 `pom.xml` 依赖版本集中管理。

---

## 5. 仓库结构

```
event-driven-integration-hub/
├── README.md                    # GitHub 首页（英文，可直接发布）
├── .gitignore
├── .env.example                 # 本地环境变量模板（真实 secrets 不入库）
├── docker-compose.yml           # 基础设施：postgres + kafka（+可选 security/observability profile）
├── scripts/
│   ├── local-dev.sh             # 一键起依赖、建 topic、跑服务
│   └── init-kafka.sh            # 创建 topic（compact / dlq / 分区数）
├── services/
│   ├── pom.xml                  # 父 POM（依赖版本集中管理）
│   ├── integration-api/         # 渠道入口：REST + 发件箱（:8080）
│   ├── profile-service/         # 下游①：核心系统模拟（:8081）
│   └── data-loader/             # 下游②：数据平台模拟（:8082）
├── k8s/                         # Deployment/Service/ConfigMap/Secret/HPA 清单
├── docs/
│   ├── design.md                # 本文档
│   ├── decisions.md             # 关键技术决策记录（ADR-lite）
│   ├── resume.md                # 简历措辞 + 面试讲解脚本
│   └── architecture-diagram.html# 架构示意图
└── .github/workflows/
    ├── ci.yml                   # 构建 + 测试 + 镜像 + kind 冒烟
    └── release.yml              # tag 触发：发布镜像（可选）
```

---

## 6. 服务职责与包结构

三个服务都是独立可部署的 Spring Boot 应用，共用父 POM。建议包结构（以 integration-api 为例，其余类似）：

```
com.example.hub.api
├── ApiApplication.java
├── config/        # SecurityConfig、KafkaConfig、JacksonConfig
├── controller/    # CustomerController、DlqController(在 profile-service)
├── service/       # CustomerService、OutboxRelay、IdempotencyService
├── messaging/     # EventPublisher（事务性生产者）、Envelope 模型
├── repository/    # JPA repositories
├── model/         # 实体 + 事件信封
└── web/           # ProblemDetail 处理、OpenAPI 配置
```

### 6.1 integration-api（端口 8080）— 渠道入口
- REST：创建/更新客户档案、查询处理状态、事件历史、幂等重放。
- 安全：OAuth2 Resource Server（JWT 校验，`ROLE_BANKER` 才能写）。
- 核心逻辑：**同一事务**写 `customer` 表 + `outbox` 表；`OutboxRelay` 轮询发件箱，用**事务性 Kafka 生产者**发布（开启 `enable.idempotence` 与事务）。
- 幂等：`Idempotency-Key` 请求头 → `idempotency_keys` 表，重复请求直接返回首次结果。

### 6.2 profile-service（端口 8081）—「核心系统」模拟
- 消费 `customer.profile.events`（独立消费组，按 `customerId` 分区保证单客户顺序）。
- **幂等消费**：`processed_events` 表（eventId 唯一），重复事件直接 ACK。
- 重试：`DefaultErrorHandler` + 指数退避，超过最大次数写入 **DLQ** topic + `dlq_events` 表。
- 管理端点：`GET /admin/dlq`、`POST /admin/dlq/{eventId}/replay`（把事件重新发回主 topic，演示"修复后重放"）。
- 成功后把最新客户档案 upsert 到 `profile_store` 表（模拟核心系统）。

### 6.3 data-loader（端口 8082）—「数据平台」模拟
- 消费同一 topic 的**另一个消费组**，演示一对多扇出。
- 事件携带状态传输：事件 payload 内含完整客户快照，直接 upsert 到 `data_warehouse` 宽表（模拟数仓/湖的加载）。

---

## 7. API 设计（integration-api）

| 方法 | 路径 | 说明 | 成功码 |
| --- | --- | --- | --- |
| POST | `/api/v1/customers` | 创建客户档案，需 `Idempotency-Key` 头 | 202 Accepted（异步） |
| PATCH | `/api/v1/customers/{customerId}` | 部分更新（改手机号/地址/KYC） | 202 Accepted |
| GET | `/api/v1/customers/{customerId}` | 客户当前状态 + 处理状态（PENDING/PROCESSED/FAILED） | 200 |
| GET | `/api/v1/customers/{customerId}/events` | 事件历史（发件箱审计，可做重放检查） | 200 |
| GET | `/actuator/health` `/actuator/prometheus` | 健康检查 / 指标 | 200 |
| GET | `/v3/api-docs` `/swagger-ui.html` | OpenAPI 契约 / UI | 200 |

请求/响应示例（核心是 **202 + Location + eventId**，体现"异步集成"心智）：

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

错误统一为 ProblemDetail（RFC 9457）：`400` 校验失败、`401/403` 未认证/越权、`409` 幂等键冲突、`422` 业务校验、`500` 内部错误。

---

## 8. 数据模型

**integration-api 库**（单库多表即可，POC 不必拆库）：

```sql
customer(id BIGSERIAL PK, customer_id VARCHAR UNIQUE, name, email, phone,
         kyc_status, version INT, created_at, updated_at)
outbox(id BIGSERIAL PK, event_id UUID UNIQUE, aggregate_id VARCHAR, type VARCHAR,
       payload JSONB, status VARCHAR,             -- PENDING / PUBLISHED
       created_at, published_at)
idempotency_keys(idempotency_key VARCHAR PK, request_hash VARCHAR,
                 response_body JSONB, created_at)
```

**profile-service 库**：`profile_store(customer_id PK, snapshot JSONB, version INT, updated_at)`；`processed_events(event_id PK, customer_id, processed_at)`；`dlq_events(event_id PK, topic, payload JSONB, reason, retried_at, status)`。

**data-loader 库**：`data_warehouse(customer_id PK, snapshot JSONB, loaded_at)`。

> POC 用 JPA + Flyway 管理 schema 迁移（Flyway 也是简历点：数据库变更受版本控制）。

---

## 9. Kafka 设计

| 项 | 设计 |
| --- | --- |
| Broker | 本地单节点 KRaft（无 ZooKeeper），docker-compose 一键起 |
| 主 topic | `customer.profile.events`，`partitions=3`，`cleanup.policy=compact`（保留每客户最新状态，支持重放/新消费者追赶） |
| DLQ topic | `customer.profile.events.dlq` |
| 分区键 | `customerId` → 同一客户事件有序 |
| 投递语义 | 生产者事务 + `enable.idempotence=true`；消费者 `enable.auto.commit=false` + 手动 ACK；整体呈 **at-least-once**，配合消费端幂等 → 逻辑上 exactly-once 效果 |
| 事件信封 | `{eventId, type, schemaVersion, occurredAt, customerId, sourceChannel, traceId, payload}`，schemaVersion 从 1 开始，payload 为完整客户快照（ECST） |

事件类型：`CustomerCreated` / `CustomerUpdated` / `AddressChanged`。新版本字段演进：`schemaVersion` 升到 2 并保留向后兼容（消费者按版本分支处理）——这是面试可讲的"消息契约演进"话题。

topic 创建脚本（`scripts/init-kafka.sh`）：

```bash
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic customer.profile.events \
  --partitions 3 --replication-factor 1 --config cleanup.policy=compact
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic customer.profile.events.dlq --partitions 3 --replication-factor 1
```

---

## 10. 集成模式清单（面试重点）

| 模式 | 在哪实现 | 解决什么问题 | 面试一句话 |
| --- | --- | --- | --- |
| 事务性发件箱 | integration-api：customer+outbox 同事务，relay 发布 | 双写不一致（DB 写成功但消息没发） | "我用发件箱保证数据库与消息的原子性，避免分布式事务" |
| 幂等消费 | profile-service：`processed_events` 唯一键 | at-least-once 投递下的重复消息 | "消费端用事件 ID 去重，重复投递不产生副作用" |
| 死信队列 + 重放 | 重试超限写 DLQ，`/admin/dlq/{id}/replay` 重放 | 坏消息阻塞消费进度 | "无法处理的消息进 DLQ，修复后一键重放，生产可运维" |
| 扇出（Pub/Sub） | 两个独立消费组消费同一 topic | 一份变更同步多个下游 | "Kafka 让一次变更同时到达核心系统与数据平台，互不阻塞" |
| 事件携带状态传输 | data-loader：payload 即完整快照，直接写宽表 | 下游无需回查上游 | "事件自带完整状态，数据平台零依赖回查" |
| 重试 + 退避 | DefaultErrorHandler + 指数退避 | 瞬时故障自愈 | "瞬时故障指数退避重试，避免打爆下游" |
| 消息契约版本化 | 信封 `schemaVersion` | 演进不破坏消费者 | "事件带版本号，兼容演进" |
| 安全服务间通信 | JWT/OAuth2 + 文档化 mTLS 扩展 | API 保护 | "API 用 OIDC 保护，服务间通信可升级 mTLS" |

---

## 11. 可观测性设计

- **结构化日志**：logstash-logback-encoder 输出 JSON，MDC 注入 `traceId / eventId / customerId / service`；**PII 掩码**（邮箱、手机号在日志中打码——银行场景加分项）。
- **指标**：Micrometer 自定义 counter/timer：`hub.events.published`、`hub.events.consumed`、`hub.events.dlq`、`hub.retries`、消费延迟；配合 Kafka 客户端指标。
- **追踪**：Micrometer Tracing + Brave，Kafka 消息头透传 `traceId`，Zipkin 展示端到端链路（POST → 发布 → 消费 → 落库）。
- **健康检查**：liveness/readiness 探针（含 Kafka、DB 依赖检查），k8s 清单直接使用。

---

## 12. 安全设计

- **API 保护**：integration-api 配 OAuth2 Resource Server，从 Keycloak 的 JWKS 校验 JWT；`ROLE_BANKER` 才能写，读接口也要认证（演示最小权限）。
- **密钥管理**：本地用 `.env`（不入库），k8s 用 Secret；容器内不硬编码凭据。
- **日志与响应**：PII 掩码；错误信息不泄露内部细节。
- **扩展方案（文档化即可）**：服务间 mTLS（自签 CA 脚本）、Kafka SASL/SSL、API 网关（Apisix/Kong/Spring Cloud Gateway）前置限流与审计。面试可答"我知道生产环境还需要什么，并且给出了落地路径"。

---

## 13. 测试策略

| 层级 | 内容 | 工具 |
| --- | --- | --- |
| 单元测试 | 发件箱 relay 状态机、幂等逻辑、事件信封序列化 | JUnit 5 + Mockito |
| 集成测试 | **Testcontainers 起 Postgres + Kafka**：① POST → outbox → 事件发布 → 消费 → 状态落库全链路；② 消费者抛错 → 重试 → 进 DLQ → replay 恢复；③ 重复事件被幂等忽略；④ 扇出两个消费组都收到 | Testcontainers |
| 契约/文档 | OpenAPI 文件即契约；swagger-ui 可交互 | springdoc |
| CI 冒烟 | kind 集群部署三服务 → curl 健康检查 → 发一条 POST 验证端到端 | GitHub Actions |

> Testcontainers 是本次 POC 的重要简历点："集成测试不依赖任何外部环境，CI 里也能稳定运行"。

---

## 14. CI/CD 与容器化

**GitHub Actions `ci.yml` 流水线（三段）**：

```
build:    JDK 21 + Maven → mvn verify（含 Testcontainers 集成测试）→ 上传测试报告
docker:   (main/tag) buildx 多平台构建 → 推送 GHCR（integration-api / profile-service / data-loader）
smoke:    (main) kind 起集群 → 应用 k8s 清单 → 等就绪 → curl 健康检查 → 打印结果
```

**镜像规范（OpenShift 兼容）**：
- 多阶段构建：`eclipse-temurin:21-jdk` 编译 → JRE 运行层；
- 非 root 用户（`UID 10001`）、`USER 10001:0`（允许 OpenShift 随机 UID 映射）；
- 最小化层、`ENTRYPOINT` 用 exec 形式。

**k8s 清单要点（示例见 design.md 附录）**：Deployment（replicas=2、探针、资源限制）、Service、ConfigMap、Secret、HPA、OpenShift Route 说明。Kafka 部署在生产用 Strimzi Operator（本地用 compose 即可，文档化说明）。

---

## 15. 本地运行指南

```bash
# 0. 前置：Docker Desktop（Windows）+ JDK 21 + Maven
git clone <your-repo-url> && cd event-driven-integration-hub
cp .env.example .env

# 1. 起基础设施（Postgres + Kafka；可选 --profile security 加 Keycloak，--profile observability 加 Zipkin/Prometheus）
docker compose up -d
./scripts/init-kafka.sh        # 建 topic

# 2. 跑服务（方式 A：本地 JVM）
cd services && mvn -pl integration-api spring-boot:run   # 同理 profile-service / data-loader

# 3. 端到端验证
curl -X POST http://localhost:8080/api/v1/customers \
  -H "Idempotency-Key: demo-001" -H "Content-Type: application/json" \
  -d '{"customerId":"CUS-0001","name":"Jane Doe","email":"jane@example.com","phone":"+64 21 000 0000","addresses":[],"kycStatus":"PENDING"}'
# 观察：Kafka topic 消费、profile-store 落库、data_warehouse 落库
# 触发失败：用一个 consumer 必然抛错的 payload → 观察重试 → DLQ → /admin/dlq/{id}/replay
```

---

## 16. 里程碑路线图（按这个节奏做，每阶段都有可展示产出）

| 阶段 | 内容 | 练习点 / 可交付 | 建议用时 |
| --- | --- | --- | --- |
| **M0** | 建仓库：README、.gitignore、docker-compose（postgres+kafka）、父 POM | Git 工作流、Docker Compose | 半天 |
| **M1** | integration-api：REST CRUD + JPA + Flyway + OpenAPI + 健康检查 | Spring Boot 基础、Spring Data JPA | 1–2 个周末 |
| **M2** | 发件箱 + Kafka 生产者；profile-service 消费者 + 幂等 + 重试 + DLQ + 重放 | **Kafka 核心**（生产者/消费者/分区/重试/DLQ）——本项目最核心故事 | 1–2 个周末 |
| **M3** | data-loader 扇出；可观测性三件套；Testcontainers 全链路测试；CI 流水线 | Micrometer/追踪/JSON 日志；**CI/CD**；测试工程化 | 1–2 个周末 |
| **M4** | Keycloak JWT 保护；k8s 清单 + kind 冒烟；README 最终打磨 + 简历文案 | **OpenShift/K8s 部署概念**；安全 | 1 个周末 |

> 优先保证 **M2 完成**——发件箱 + Kafka + DLQ 是简历和面试的核心。M3/M4 是"生产级"加分项，做不完也不影响主线故事。

---

## 17. 面试与简历

完整措辞与讲解脚本见 **`docs/resume.md`**。核心叙事一句话：

> "我独立设计并实现了一个事件驱动的银行集成中枢：Spring Boot REST API 用事务性发件箱把客户档案变更发布到 Kafka，两个下游（核心系统、数据平台）幂等消费、失败进死信队列可重放；整个系统配了 JSON 结构化日志、Prometheus 指标、Zipkin 追踪，用 Testcontainers 跑真实集成测试，GitHub Actions 构建、打包、推到 GHCR，并在 kind 集群里做部署冒烟。"

---

## 18. 扩展方向（做完主线后的加分项，按性价比排序）

1. **Schema Registry**（Apicurio/Confluent）：消息契约集中管理 + 兼容性检查——直接对位"集成平台"心智。
2. **mTLS 服务间通信**：脚本生成自签 CA，展示证书与双向认证（JD 提到 certificates）。
3. **API 网关前置**（Apisix / Spring Cloud Gateway）：限流、审计、路由——对位 JD 的 "API gateway / API management"。
4. **Kafka SASL/SSL**：从明文升级认证加密，展示安全深度。
5. **契约测试 Pact**：消费者驱动契约，验证"提供方/消费方"演进。
6. **Spring Boot 4.x 升级**：展示版本演进能力。
7. **Grafana 仪表盘 JSON**：指标可视化成品，README 放截图。
8. **Strimzi 部署 Kafka 到 kind**：把"Kafka 上 K8s"也补上。

---

## 附录 A：k8s 清单示例（integration-api）

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
          image: ghcr.io/<your-user>/integration-hub/integration-api:latest
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

> OpenShift 说明：镜像以非 root + 任意 UID 运行、无特权容器需求，可默认通过 SCC（restricted）。生产环境 Kafka 建议由 Strimzi Operator 管理。
