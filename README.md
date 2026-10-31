# FinFlow: Mini Core-Banking Microservice

**FinFlow** is a modern, enterprise-ready banking transaction backend service built to process atomic, high-performance peer-to-peer (P2P) money transfers while maintaining an immutable historical ledger audit log. This project demonstrates a modern Java architectures following strict production-grade patterns.

---

## 🛠️ Tech Stack & Highlights

*   **Java 21 Engine**: Leverages cutting-edge LTS capabilities including **Java Records** for immutable Data Transfer Objects (DTOs).
*   **Spring Boot 4.x Framework**: Implements a structured **Controller-Service-Repository** clean architecture with full RESTful API exposure.
*   **Database Evolution (Flyway)**: Utilizes version-controlled schema definitions, completely avoiding brittle auto-generation logic inside production boundaries.
*   **Microsoft SQL Server Integration**: Connects dynamically to a containerized SQL Server relational layer via explicit JDBC configurations.
*   **Multi-User Concurrency Guard**: Features explicit **Pessimistic Writing Locks** (`@Lock(LockModeType.PESSIMISTIC_WRITE)`) to eliminate double-spending, data racing, and balance anomalies.
*   **ACID Compliance**: Enforces explicit transactional isolation parameters (`Isolation.REPEATABLE_READ`) to guarantee calculation boundaries and atomic safe-rollbacks on operational faults.
*   **Robust Automated Testing**: Covered by automated unit testing suites built using **JUnit 5** and isolated dependency mocks with **Mockito**.

---

## 🏛️ Domain Architecture Schema

The application handles relational persistence via two highly optimized table mappings linked to a dedicated SQL Server container:

### 1. `accounts` Table
Tracks user balances and incorporates a data entity version field to block structural conflict updates:
*   `id` (`VARCHAR(36)`, Primary Key) - Locally generated UUID string coordinates.
*   `account_number` (`VARCHAR(20)`, Unique, Indexed) - Public routing account string identifier.
*   `owner_name` (`VARCHAR(255)`) - Account ownership holder registration metadata.
*   `balance` (`DECIMAL(18,2)`) - Active fractional currency representation.
*   `version` (`BIGINT`) - Hibernate concurrency tracker value.

### 2. `transactions` Table
An immutable accounting audit ledger designed to preserve tracking accountability:
*   `id` (`VARCHAR(36)`, Primary Key) - Distinct tracking transaction receipt code string.
*   `source_account_number` (`VARCHAR(20)`) - Originating account source routing.
*   `target_account_number` (`VARCHAR(20)`) - Destination account credit target.
*   `amount` (`DECIMAL(18,2)`) - Transferred currency absolute value.
*   `timestamp` (`DATETIME2`) - Precise ISO execution clock capture.
*   `status` (`VARCHAR(50)`) - State engine checkpoint (e.g., `SUCCESS`, `FAILED`).

---

## 🚀 Local Installation & Quick Start

### Prerequisites
Make sure your development machine has the following dependencies configured globally:
*   **Java 21 JDK** (e.g., Eclipse Temurin, Oracle OpenJDK)
*   **Apache Maven 3.9+**
*   **Docker Desktop** (Engine running background processes)

### Step 1: Clone and Infrastructure Boot up
Navigate to your project path folder containing your `docker-compose.yml` file and start the Microsoft SQL Server database layer:
```powershell
docker compose up -d
```
Verify the container is active and listening on port `1433`:
```powershell
docker ps
```

### Step 2: System Compilation
Execute the clean build lifecycles using your local terminal to pull down starter definitions and ensure syntax validations pass:
```powershell
mvn clean compile
```

### Step 3: Run the Application Server
Launch the Spring Boot microservice environment. Flyway will automatically catch the startup lifecycle, provision the `finflow` catalog database, apply the SQL migrations, and bind the embedded server onto port `8080`:
```powershell
mvn spring-boot:run
```

---

## 🌐 REST API Specifications

### 💳 Execute Peer-to-Peer Transfer
*   **Endpoint:** `POST /api/v1/transfers`
*   **Content-Type:** `application/json`

#### Request Payload Body Example:
```json
{
    "sourceAccountNumber": "ACC123",
    "targetAccountNumber": "ACC456",
    "amount": 250.50
}
```

#### Successful Response JSON Output (`200 OK`):
```json
{
    "id": "e2c34a1b-7d8e-90f1-23a4-56b7c8d9e0f1",
    "sourceAccountNumber": "ACC123",
    "targetAccountNumber": "ACC456",
    "amount": 250.50,
    "timestamp": "2026-10-31T22:15:30.12456",
    "status": "SUCCESS"
}
```

---

## 🧪 Testing and Quality Control

The service business layer is fully isolated and verified via structural test coverage rules. To run your unified unit test validation suite using **JUnit 5** and **Mockito**, execute:

```powershell
mvn clean test
```

### Verified Scenarios:
1.  `shouldExecuteTransferSuccessfully`: Assures balances are deducted from source and credited to targets atomically while writing a clean transaction row tracking audit.
2.  `shouldThrowExceptionWhenFundsAreInsufficient`: Validates that balance safety thresholds are blocked, preventing overdrafts and verifying that ledger updates are never committed to the database.

---