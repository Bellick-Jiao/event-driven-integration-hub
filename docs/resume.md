# 简历与面试素材（resume.md）

> 用法：GitHub 项目描述、简历项目条目、LinkedIn 项目栏，以及面试自我介绍/项目深挖环节。
> 原则：**动词开头、可量化、贴 JD 关键词**（Spring Boot、REST、integration patterns、Kafka、observability、CI/CD、Kubernetes、security）。

---

## 1. GitHub 项目一句话简介（放仓库 About）

**Event-Driven Integration Hub** — Spring Boot 3 + Kafka integration hub demonstrating transactional outbox, idempotent consumers, DLQ with replay and event fan-out, with observability, OAuth2 security, Testcontainers tests and GitHub Actions CI/CD.

## 2. 简历项目条目（英文，2 条备选，按字数取舍）

### 版本 A（详版，适合投后端/集成岗位）

> **Event-Driven Integration Hub — Personal Project** (Spring Boot 3, Java 21, Kafka, PostgreSQL, Docker, Kubernetes)
> - Designed and built a bank-style event-driven integration hub: a REST API accepts customer-profile changes and publishes them to Kafka via a **transactional outbox**, fanning out to two independent consumers (core-system simulator and data-platform loader) without loss or duplication.
> - Implemented production reliability patterns: **idempotent consumers** (dedupe by event ID), **exponential-backoff retry**, **dead-letter queue with one-click replay**, event-carried state transfer, and versioned event envelopes.
> - Added full **observability**: structured JSON logs with PII masking, Micrometer + Prometheus metrics, Zipkin distributed tracing across the Kafka pipeline, readiness/liveness probes.
> - Secured the API with **OAuth2/OIDC** (Keycloak JWKS validation, role-based access) and containerized services as **non-root, OpenShift-friendly images**.
> - Wrote end-to-end **Testcontainers** integration tests (Postgres + Kafka) covering publish→consume→store, retry→DLQ→replay and idempotent dedup; built a **GitHub Actions** pipeline (build → tests → GHCR images → kind-cluster smoke deploy) and Kubernetes manifests with probes and HPA.

### 版本 B（精简版，半行左右）

> **Event-Driven Integration Hub** — Spring Boot 3 + Kafka integration POC: REST API with transactional outbox, idempotent consumers, DLQ + replay and event fan-out; observability (logs/metrics/tracing), OAuth2 security, Testcontainers integration tests, GitHub Actions CI/CD to GHCR + kind.

## 3. LinkedIn / 个人网站版（段落式，第一人称）

> "I built an event-driven integration hub to practice the engineering patterns banks rely on to keep systems connected and reliable. A Spring Boot REST API persists customer changes and publishes them to Kafka through a transactional outbox; two downstream consumers (a core-system simulator and a data-platform loader) process the same event stream independently with idempotency, retries and a dead-letter queue that supports replay. The project is fully observable (JSON logs, Prometheus metrics, Zipkin tracing), secured with OAuth2/OIDC, tested with Testcontainers against real Postgres and Kafka, and shipped through a GitHub Actions pipeline that builds, tests, publishes images and smoke-deploys into a kind cluster. This was my way of getting hands-on with the stack I want to grow into: Spring Boot, Kafka, CI/CD and Kubernetes/OpenShift."

## 4. 面试讲解脚本（2 分钟版本）

**开场（30 秒）——一句话定位**
> "我最近做了一个事件驱动的集成中枢 POC。业务场景是银行场景里很典型的：渠道端提交客户档案变更，要同时落到核心系统和数据平台，还要保证不丢、不重、可重放。我选了 Spring Boot + Kafka 来做。"

**讲核心机制（60 秒）——按"问题 → 方案"讲**
1. "第一个问题是双写一致性。数据库写了、消息没发出去就会丢数据。我用**事务性发件箱**：业务写和事件写在同一事务里，后台 relay 轮询发件箱再发布，天然不会丢。"
2. "第二个问题是重复消费。Kafka 是 at-least-once，我用**事件 ID 幂等去重**，重复消息直接跳过。"
3. "第三个问题是坏消息阻塞。消费失败先**指数退避重试**，超过次数进**死信队列**，我做了管理接口可以一键重放。"
4. "最后是扇出：两个下游用**独立消费组**消费同一个 topic，互不影响、各自扩展。"

**讲生产级细节（30 秒）**
> "这个项目不是只有 CRUD：JSON 结构化日志带 traceId 和事件 ID，Prometheus 出指标，Zipkin 能看到从 HTTP 到 Kafka 到落库的完整链路；API 用 OAuth2/JWT 保护；集成测试用 Testcontainers 起真实的 Postgres 和 Kafka 跑全链路；CI 构建、测试、推镜像到 GHCR，再在 kind 集群里做部署冒烟，镜像按非 root 用户构建，可以直接跑在 OpenShift 上。"

**收尾（20 秒）——链接到岗位**
> "做完这个项目，我对集成开发里最关键的可靠性模式有了手感，也对你们的 Customer360 这类'连接核心系统、数据平台和渠道'的场景有直观理解。GitHub 仓库在简历里，欢迎你看，我也可以现场演示端到端流程，包括故意让消费失败、看它进 DLQ 再重放。"

## 5. 面试可能追问 & 应答要点

| 追问 | 应答要点 |
| --- | --- |
| 为什么用发件箱不用 Kafka 事务消息？ | 发件箱把"业务库"当消息源，方案简单、跨团队可落地；Kafka 事务消息需要 broker 与业务库同一事务域，生产里通常不可行 |
| 重复消费你怎么保证幂等？ | 消费侧 `processed_events` 唯一约束 + 先查后插；只对"写"操作幂等，读天然幂等 |
| DLQ 里消息怎么恢复？ | 管理接口把事件重新发回主 topic，消费者重放；payload 自带版本号，兼容演进 |
| 分区数怎么定？ | 3 个分区 + 按 customerId 分区键保证单客户有序；扩展性上说明"分区数决定并发上限" |
| 单点 Kafka 能说明什么？ | 本地 POC 单节点够用；生产加副本 + 多 broker，或用 Strimzi 上 K8s——设计文档里写明了扩展路径 |
| 为什么镜像要非 root？ | OpenShift 默认 restricted SCC 拒绝 root/特权容器；非 root + 任意 UID（`USER 10001:0`）保证开箱即用 |
| OpenShift 你实际跑过吗？ | 诚实回答：本地用 kind 验证 K8s 清单，镜像按 OpenShift 兼容标准构建（非 root、无特权、只读根文件系统可选）；这是我最想在生产环境继续补的部分 |

## 6. 与现有 payment-hub 项目怎么区分（避免简历重复）

| | payment-hub（已有） | event-driven-integration-hub（本 POC） |
| --- | --- | --- |
| 主题 | 支付流程/账务 | **系统间集成与可靠性模式** |
| 重点 | 业务 CRUD + 支付状态 | 发件箱、幂等、DLQ、扇出、可观测性、CI/CD |
| 技能增量 | Spring Boot 基础 | **Kafka、CI/CD、K8s/OpenShift、Testcontainers、安全** |

简历上两个项目放一起就是完整叙事：**"先会写业务服务，再做企业级集成"**。
