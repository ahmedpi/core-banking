# FinFlow: Event-Driven Core-Banking Microservice

**FinFlow** is a modern, enterprise-grade banking transaction backend service engineered to process atomic,
high-concurrency peer-to-peer (P2P) money transfers while guaranteeing reliable event delivery via the **Transactional
Outbox Pattern** and **Apache Kafka**.

This project demonstrates production-ready Java 21 and Spring Boot 4 architecture patterns designed for financial data
integrity, high availability, and cloud-native scalability.

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

## 🔍 Local Verification (Database & Kafka)

### 1. Verify Outbox Relay Status in SQL Server

```powershell
docker exec -it finflow-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "Password123" -C -d finflow -Q "SELECT id, aggregate_id, event_type, status, published_at FROM outbox_events;"
```

### 2. Inspect Messages via Kafka UI (Browser)

Open **`http://localhost:8090`** in your browser to inspect the `finflow-local` cluster:
1. Navigate to **Topics** -> **`finflow.transfers.completed`** (auto-created upon the first transfer).
2. Open the **Messages** tab to inspect partition keys (`ACC123`), timestamps, and formatted JSON payloads.

### 3. Consume Published Events via Kafka CLI (Terminal)

To stream messages directly from the Kafka container with partition keys and timestamps:

```powershell
docker exec -it finflow-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic finflow.transfers.completed --from-beginning --property print.key=true --property key.separator=" | " --property print.timestamp=true
```

## 🧪 Testing

Run the JUnit 5 & Mockito unit test suite:

```powershell
mvn clean test
```

## 🗺️ Production Roadmap (Next Steps)

### Phase 2: Automated Integration & Concurrency Verification (`Testcontainers`)
* **Containerized Infrastructure**: Spin up ephemeral **Microsoft SQL Server 2022** and **Apache Kafka 3.9 (KRaft)** instances via JUnit 5 `@Testcontainers` with automatic Flyway migration execution.
* **Bidirectional Concurrency Stress Suite**: Execute multi-threaded transfers (`ACC123 <-> ACC456`) synchronized via `CountDownLatch` to prove deterministic lock ordering prevents deadlocks and double-spending under high load.
* **End-to-End Outbox Verification**: Assert atomic ledger-to-outbox persistence, rollback integrity on insufficient funds, and asynchronous relay delivery to `finflow.transfers.completed` using **Awaitility**.

### Phase 3: Resilient Event Consumption & Spring AI Anomaly Detection
* **Resilient Kafka Consumer (`TransactionAnomalyConsumer`)**: Consume `TransferCompletedEvent` messages with manual offset commits (`ack-mode: manual`), non-blocking retry topics (`@RetryableTopic`), and Dead Letter Topic (`finflow.transfers.completed-dlt`) routing.
* **Consumer Idempotency & Velocity Context**: Persist alerts into a Flyway-managed `fraud_alerts` table (`V3`) with unique transaction constraints while querying 1-hour account transfer velocity to enrich risk evaluation.
* **LLM Risk Scoring (`Spring AI`)**: Integrate `ChatClient` (backed by local **Ollama** in Docker Compose or **OpenAI**) with structured output mapping into an immutable `FraudAssessment` Java Record (`riskScore >= 75` triggers high-risk alerts).

### Phase 4: API Hardening & Cloud-Native Readiness
* **HTTP Idempotency Keys**: Enforce a client-generated `Idempotency-Key` header on `POST /api/v1/transfers` backed by a `V4` persistence table to return cached receipts on network retries and reject payload mismatches (`409 Conflict`).
* **Security, OpenAPI & Observability**: Add **Spring Security (OAuth2 Resource Server / JWT)** via a dedicated security profile, interactive **Springdoc OpenAPI / Swagger UI** (`/swagger-ui.html`), and **Actuator / Prometheus** metrics for outbox lag monitoring.
