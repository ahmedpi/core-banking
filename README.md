# FinFlow: Event-Driven Core-Banking Microservice

**FinFlow** is a modern, enterprise-grade core-banking transaction service engineered to process atomic,
high-concurrency peer-to-peer (P2P) money transfers, guarantee reliable event delivery via the **Transactional Outbox
Pattern** and **Apache Kafka**, and perform **real-time AI-powered fraud risk scoring** using **Spring AI** and local
**Ollama** LLMs with automatic deterministic rule-based fallback.

This project demonstrates production-ready **Java 21**, **Spring Boot 4**, and **Spring AI 2** architecture patterns
designed for financial data integrity, fault-tolerant event processing, structured LLM evaluation, and cloud-native
scalability.

---

## 🛠️ Tech Stack & Architectural Highlights

* **Java 21 LTS & Spring Boot 4.x**: Clean layered architecture leveraging **Java Records** for immutable Data Transfer
  Objects (DTOs) and domain event contracts.
* **Multi-User Concurrency & Deadlock Prevention**:
    * Explicit **Pessimistic Write Locks** (`@Lock(LockModeType.PESSIMISTIC_WRITE)`) prevent double-spending and lost
      updates.
    * **Deterministic Lexicographical Lock Ordering** sorts source and target account numbers prior to row acquisition,
      eliminating circular database deadlocks under bidirectional concurrent load.
* **ACID Compliance & UTC Precision**: Enforces `Isolation.REPEATABLE_READ` transactions with full rollback guarantees
  and UTC `Instant` timestamps.
* **Transactional Outbox Pattern**: Atomically persists `TransferCompletedEvent` records into an `outbox_events` table
  within the same SQL transaction as the ledger update, preventing dual-write inconsistencies between SQL Server and
  Kafka.
* **Tiered Hybrid Fraud Risk Evaluation**: Combines deterministic velocity and volume rules (`RuleBasedFraudRiskEvaluator`) with local **Ollama** LLM behavioral reasoning (`SpringAiFraudRiskEvaluator`) to balance low-latency alerting with deep contextual analysis.
* **Event-Driven Messaging (Apache Kafka 4 / KRaft)**:
    * Dedicated scheduled outbox relay (`OutboxPublisherJob`) polls pending events and publishes them with `acks=all`
      and idempotent producer guarantees, partitioned by `account_number` for strict per-account ordering.
    * Environment-aware Spring Profiles (`default` for local Docker KRaft Kafka; `prod` for **`SASL_SSL` + `OAUTHBEARER`
      ** client authentication).
* **Database Evolution (Flyway)**: Version-controlled schema migrations (`V1`, `V2`) with Hibernate `ddl-auto=validate`
  enforcement against Microsoft SQL Server 2022.
* **Standardized API Error Handling (RFC 9457)**: Global `@RestControllerAdvice` returning structured `ProblemDetail`
  payloads for domain exceptions (`AccountNotFoundException`, `InsufficientFundsException`).

---

## 🛡️ Tiered Fraud Risk Scoring Engine

Transfers are evaluated in real time against single-transaction amounts and 1-hour rolling account velocity metrics before deciding whether to short-circuit or invoke the local Ollama LLM:

| Risk Tier | Base Score | Single Transfer Amount | 1-Hour Rolling Volume | 1-Hour Transfer Count | Routing & Action |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **HIGH** | `85` | `>= 5000.00` | `>= 10000.00` | `>= 5` transfers | Immediate deterministic alert persisted to `fraud_alerts` (bypasses LLM latency) |
| **MEDIUM** | `55` | `1500.00` – `4999.99` | `4000.00` – `9999.99` | `3` – `4` transfers | Routed to local **Ollama** (`llama3.2`) for structured JSON behavioral analysis |
| **LOW** | `15` | `< 1500.00` | `< 4000.00` | `< 3` transfers | Approved immediately as baseline activity |

### Evaluation & Resilience Rules

* **Top-Down Precedence:** Rules are evaluated strictly from `HIGH` to `LOW` using `>=` thresholds; triggering any single condition within a tier immediately assigns that risk level.
* **Fail-Fast LLM Resilience (`max-attempts: 1`):** Spring AI is configured with single-attempt execution (`10s` connect timeout, `60s` read timeout) and bounded token generation (`num-predict: 120`, `num-ctx: 2048`, `temperature: 0.1`, `format: json`) so slow or unavailable inference never blocks the manual-acknowledgment Kafka consumer (`finflow-ai-fraud-detector`).
* **Automatic Deterministic Fallback:** If the local **Ollama** instance (`http://localhost:11434`) is unreachable, times out, or fails structured JSON parsing during a `MEDIUM`-risk evaluation, the engine immediately falls back to `RuleBasedFraudRiskEvaluator` (`score: 55`, `level: MEDIUM`) and commits the Kafka offset safely without data loss.

---

## 🏛️ Domain & Outbox Architecture Schema

### 1. `accounts` Table

Tracks active account balances and concurrency metadata:

* `id` (`VARCHAR(36)`, Primary Key) — UUID identifier.
* `account_number` (`VARCHAR(20)`, Unique, Indexed) — Public routing account identifier.
* `owner_name` (`VARCHAR(255)`) — Account holder metadata.
* `balance` (`DECIMAL(18,2)`) — Active fractional currency balance.
* `version` (`BIGINT`) — Optimistic concurrency tracker.

### 2. `transactions` Table

Immutable accounting audit ledger:

* `id` (`VARCHAR(36)`, Primary Key) — Transaction receipt UUID.
* `source_account_number` (`VARCHAR(20)`) — Originating account routing number.
* `target_account_number` (`VARCHAR(20)`) — Destination account routing number.
* `amount` (`DECIMAL(18,2)`) — Transferred currency value.
* `timestamp` (`DATETIME2`) — UTC execution timestamp.
* `status` (`VARCHAR(50)`) — Execution state (`SUCCESS`, `FAILED`).

### 3. `outbox_events` Table

Transactional event staging log ensuring at-least-once Kafka delivery:

* `id` (`VARCHAR(36)`, Primary Key) — Outbox event UUID.
* `aggregate_type` (`VARCHAR(50)`) — Domain aggregate category (e.g., `ACCOUNT`).
* `aggregate_id` (`VARCHAR(36)`) — Kafka partition key (`source_account_number`).
* `event_type` (`VARCHAR(100)`) — Event schema identifier (`TransferCompletedEvent`).
* `payload` (`NVARCHAR(MAX)`) — Serialized JSON event payload.
* `status` (`VARCHAR(20)`) — Relay state (`PENDING`, `PUBLISHED`).
* `created_at` / `published_at` (`DATETIME2`) — Lifecycle audit timestamps.

---

## 🚀 Local Installation & Quick Start

### Prerequisites

* **Java 21 JDK** (Eclipse Temurin / OpenJDK)
* **Apache Maven 3.9+**
* **Docker Desktop**

### Step 1: Start Infrastructure (SQL Server + Kafka KRaft)

Launch Microsoft SQL Server 2022 (with automated database initialization) and Apache Kafka 3.9 (KRaft mode):

```powershell
docker compose up -d
```

Verify containers are healthy and running:

```powershell
docker ps
```

### Step 2: Run the Microservice

Flyway automatically applies all SQL migrations on startup and starts the Outbox Relay Scheduler:

```powershell
mvn spring-boot:run
```

*(To activate production OAuth2 Kafka security settings, run with `-Dspring-boot.run.profiles=prod` and
configure `KAFKA_CLIENT_ID`, `KAFKA_CLIENT_SECRET`, and `OAUTH_TOKEN_URI`.)*

### Step 3: Reset Database State (Optional)
```powershell
# 1. Force stop and delete the SQL Server container
docker rm -f finflow-sqlserver

# 2. Delete the named volume holding SQL Server data
docker volume rm core-banking_sqlserver_data

# 3. Recreate the container with a fresh, empty volume
docker compose up -d
```

---

## ⚙️ Configuration & Environment Variables

### Environment Overrides

| Variable | Default Value | Description |
| :--- | :--- | :--- |
| `DB_USERNAME` | `SA` | SQL Server authentication username |
| `DB_PASSWORD` | `Password123` | SQL Server authentication password |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Apache Kafka broker connection address |

### Spring AI & Ollama Tuning (`application.yml`)

* **Automatic Model Provisioning (`pull-model-strategy: when_missing`):** On startup, Spring AI checks the local Ollama instance (`http://localhost:11434`) and automatically pulls `llama3.2` if it is not yet cached. *(Note: The initial startup may take a few minutes depending on download speed while the model image is fetched.)*
* **Deterministic & Memory-Bounded Inference:** Configured with `temperature: 0.1` and `format: json` for reliable structured output, alongside `num-predict: 120` (output token cap), `num-ctx: 2048` (reduced KV-cache RAM footprint), and `keep-alive: 10m` to prevent cold-start delays between bursts.
* **Manual Kafka Acknowledgment:** Consumer group `finflow-ai-fraud-detector` runs with `enable-auto-commit: false` and `ack-mode: manual`, ensuring offsets are committed only after fraud evaluation and alert persistence succeed.

---
## 🌐 REST API Specifications

### Execute Peer-to-Peer Transfer

* **Endpoint:** `POST /api/v1/transfers`
* **Content-Type:** `application/json`

#### Request Payload:

```json
{
  "sourceAccountNumber": "ACC123",
  "targetAccountNumber": "ACC456",
  "amount": 100.00
}
```

#### Successful Response (`200 OK`):

```json
{
  "id": "10460c62-5914-455c-add2-3fef0f1ecbf8",
  "sourceAccountNumber": "ACC123",
  "targetAccountNumber": "ACC456",
  "amount": 100.00,
  "timestamp": "2026-11-16T16:03:04.177442800Z",
  "status": "SUCCESS"
}
```

---

## 🔍 Local Verification (Database, Kafka & AI Fraud Alerts)

### 1. List all tables and row counts in `finflow`:

```powershell
docker exec -it finflow-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U SA -P "Password123" -C -d finflow -Q "SELECT t.name AS table_name, p.rows AS row_count FROM sys.tables t INNER JOIN sys.partitions p ON t.object_id = p.object_id WHERE p.index_id IN (0, 1) ORDER BY t.name;"
```

### 2. Verify Outbox Relay & Fraud Alerts in SQL Server

Check published outbox events:

```powershell
docker exec -it finflow-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U SA -P "Password123" -C -d finflow -Q "SELECT id, aggregate_id, event_type, status, published_at FROM outbox_events;"
```

Check persisted high-risk fraud alerts (`riskScore >= 75`):

```powershell
docker exec -it finflow-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U SA -P "Password123" -C -d finflow -Q "SELECT transaction_id, account_number, risk_score, risk_level, reasoning, created_at FROM fraud_alerts;"
```

### 3. Inspect Messages via Kafka UI (Browser)

Open **`http://localhost:8090`** in your browser to inspect the `finflow-local` cluster:

1. Navigate to **Topics** -> **`finflow.transfers.completed`** (auto-created upon the first transfer).
2. Open the **Messages** tab to inspect partition keys (`ACC123`), timestamps, and formatted JSON payloads.

### 4. Consume Published Events via Kafka CLI (Terminal)

To stream messages directly from the Kafka container with partition keys and timestamps:

```powershell
docker exec -it finflow-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic finflow.transfers.completed --from-beginning --property print.key=true --property key.separator=" | " --property print.timestamp=true
```

---

## 🧪 Testing

The test suite combines fast **JUnit 5 + Mockito** unit tests (including `SpringAiFraudRiskEvaluatorTest` for structured
LLM output and fallback verification) with full **Testcontainers** integration tests (`FraudConsumerIntegrationTest`
and concurrency stress tests) backed by ephemeral SQL Server and Kafka containers.

Run the full unit and integration test suite:

```powershell
mvn clean verify
```

Run only fast unit tests:

```powershell
mvn test
```

---

## 🗺️ Production Roadmap

### Phase 2: Automated Integration & Concurrency Verification (`Testcontainers`) — ✅ Completed
* [x] **Containerized Infrastructure**: Spin up ephemeral **Microsoft SQL Server 2022** and **Apache Kafka 3.9 (KRaft)** instances via JUnit 5 `@Testcontainers` with automatic Flyway migration execution.
* [x] **Bidirectional Concurrency Stress Suite**: Execute multi-threaded transfers (`ACC123 <-> ACC456`) synchronized via `CountDownLatch` to prove deterministic lock ordering prevents deadlocks and double-spending under high load.
* [x] **End-to-End Outbox Verification**: Assert atomic ledger-to-outbox persistence, rollback integrity on insufficient funds, and asynchronous relay delivery to `finflow.transfers.completed` using **Awaitility**.

### Phase 3: Resilient Event Consumption & Spring AI Anomaly Detection — ✅ Completed
* [x] **Resilient Kafka Consumer (`TransactionAnomalyConsumer`)**: Consume `TransferCompletedEvent` messages with manual offset commits (`ack-mode: manual`), non-blocking retry topics (`@RetryableTopic`), and Dead Letter Topic (`finflow.transfers.completed-dlt`) routing.
* [x] **Consumer Idempotency & Velocity Context**: Persist alerts into a Flyway-managed `fraud_alerts` table (`V3`) with unique transaction constraints while querying 1-hour account transfer velocity to enrich risk evaluation.
* [x] **LLM Risk Scoring (`Spring AI`)**: Integrate `ChatClient` (backed by local **Ollama** in Docker Compose or **OpenAI**) with structured output mapping into an immutable `FraudAssessment` Java Record (`riskScore >= 75` triggers high-risk alerts).

### Phase 4: API Hardening & Cloud-Native Readiness — 🚧 Next Steps
* [ ] **HTTP Idempotency Keys**: Enforce a client-generated `Idempotency-Key` header on `POST /api/v1/transfers` backed by a `V4` persistence table to return cached responses for retried client requests without duplicate ledger debits.
* [ ] **Observability & Distributed Tracing**: Export OpenTelemetry traces, Micrometer metrics (outbox lag, LLM latency, fallback rate, and fraud alert counts), and Prometheus/Grafana dashboards.
* [ ] **Resilience4j Circuit Breaker**: Wrap external LLM calls with explicit circuit-breaking and bulkhead isolation to short-circuit immediately during prolonged Ollama/provider outages.
