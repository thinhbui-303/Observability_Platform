# Observability Platform

**Enterprise Log Aggregation & Real-time Observability Platform**

A full-stack, production-grade observability platform built with microservices architecture. Ingests, processes, indexes, and visualizes logs from distributed services in real-time with alerting, RBAC, and a modern React dashboard.

---

## Architecture

```
+-------------+     +--------------+     +-------+     +----------------+     +---------------+
|  App Logs   |---->|  Fluent Bit  |---->|Ingest |---->|     Kafka      |---->| Indexer Worker|
| (*.log)     |     |  (Agent)     |     |Service|     |  (raw-logs)    |     | (Bulk -> ES)  |
+-------------+     +--------------+     +-------+     +-------+--------+     +---------------+
                                                                |
                                              +-----------------+-----------------+
                                              v                 v                 v
                                     +----------------+ +--------------+ +--------------------+
                                     |Analytics Engine| |Alert Consumer| |Notification        |
                                     |(Rule Eval)     | |(Persist)     | |Dispatcher          |
                                     +-------+--------+ +--------------+ |(Slack/Telegram/WH) |
                                             |                           +--------------------+
                                             v
                                     +--------------+     +-----------------------+
                                     |  system-     |---->|      Core App         |
                                     |  alerts      |     | (REST API + WebSocket)|
                                     |  (topic)     |     +-----------+-----------+
                                     +--------------+                 |
                                                                      v
                                                              +--------------+
                                                              |   Frontend   |
                                                              | (React/Vite) |
                                                              +--------------+
```

---

## Modules

| Module | Port | Description |
|---|---|---|
| **platform-common** | - | Shared DTOs, utilities, CanonicalLogEvent schema |
| **platform-db** | - | Flyway migrations (PostgreSQL schema) |
| **ingestion-service** | 8081 | API Key auth, rate limiting, log validation, Kafka producer |
| **indexer-worker** | 8082 | Kafka consumer, Elasticsearch bulk indexing, DLQ, retry |
| **analytics-engine** | 8083 | Sliding-window rule evaluation (ERROR_SPIKE, PATTERN_MATCH) |
| **alert-consumer** | 8084 | Idempotent alert persistence to PostgreSQL |
| **notification-dispatcher** | 8085 | Multi-channel notifications (Slack, Telegram, Webhook) with Redis cooldown |
| **core-app** | 8080 | REST API (auth, log search, services, alerts, audit) + WebSocket dashboard |
| **frontend** | 5173 | React + Vite + TailwindCSS dashboard UI |

---

## Tech Stack

| Layer | Technology |
|---|---|
| **Language** | Java 21 (Virtual Threads), TypeScript |
| **Framework** | Spring Boot 3.3, React 18 |
| **Message Broker** | Apache Kafka (KRaft mode) |
| **Search Engine** | Elasticsearch 8.15 |
| **Database** | PostgreSQL 16 |
| **Cache** | Redis 7, Caffeine |
| **Log Shipper** | Fluent Bit 3.1 |
| **Auth** | JWT (Bearer), API Key (X-API-Key) |
| **Real-time** | STOMP over WebSocket |
| **Build** | Maven (multi-module), Vite |
| **Testing** | JUnit 5, Testcontainers, MockRestServiceServer |
| **API Docs** | SpringDoc OpenAPI 3 (auto-generated) |

---

## Quick Start

### Prerequisites

- **Java 21+**
- **Maven 3.9+**
- **Node.js 18+** (for frontend)
- **Docker Desktop** (for infrastructure services)

### 1. Clone and configure

```bash
git clone https://github.com/thinhbui-303/Observability_Platform.git
cd Observability_Platform

# Copy environment template
cp .env.example .env
# Edit .env if needed (defaults work for local dev)
```

### 2. Start infrastructure

```bash
docker compose up -d
```

This starts: **PostgreSQL**, **Redis**, **Kafka**, **Elasticsearch**, **Kibana**, and **Fluent Bit**.

### 3. Build all Java modules

```bash
mvn clean install -DskipTests
```

### 4. Start backend services

**Option A - PowerShell script (Windows):**

```powershell
.\run-all.ps1
```

**Option B - Manual (each in a separate terminal):**

```bash
cd ingestion-service  && mvn spring-boot:run
cd indexer-worker     && mvn spring-boot:run
cd analytics-engine   && mvn spring-boot:run
cd alert-consumer     && mvn spring-boot:run
cd notification-dispatcher && mvn spring-boot:run
cd core-app           && mvn spring-boot:run
```

### 5. Start frontend

```bash
cd frontend
npm install
npm run dev
```

Open **http://localhost:5173** and login with `admin` / `password`.

---

## Frontend Pages

| Page | Description |
|---|---|
| **Login** | JWT authentication with role-based access |
| **Dashboard** | Real-time metrics via WebSocket (Logs/sec, Error Rate, Service Health) |
| **Logs Explorer** | Full-text search with filters (service, level, time range, trace ID) |
| **Trace View** | Distributed trace timeline visualization |
| **Services** | Register services, manage API keys, enable/disable |
| **Alert Rules** | Create/edit rules (ERROR_SPIKE, PATTERN_MATCH) with notification channels |
| **Alerts** | View triggered alerts, acknowledge, resolve |
| **Audit Logs** | System-wide audit trail for all mutations |
| **DLQ Manager** | View and reprocess dead-letter queue messages |

---

## RBAC Matrix

| Endpoint | ADMIN | DEVOPS | DEVELOPER | VIEWER |
|---|:---:|:---:|:---:|:---:|
| Login | Y | Y | Y | Y |
| Search Logs | Y | Y | Y | Y |
| Dashboard (WebSocket) | Y | Y | Y | Y |
| View Alerts | Y | Y | Y | Y |
| Acknowledge Alert | Y | Y | Y | - |
| Resolve Alert | Y | Y | - | - |
| Manage Alert Rules | Y | Y | - | - |
| Manage Services | Y | - | - | - |
| View Audit Logs | Y | Y | - | - |

---

## Log Ingestion Flow

### Via API (Direct)

```bash
# Single log
curl -X POST http://localhost:8081/api/v1/telemetry/logs \
  -H "X-API-Key: <your-api-key>" \
  -H "Content-Type: application/json" \
  -d '{"level":"ERROR","message":"Connection timeout","timestamp":"2026-09-19T10:00:00Z"}'

# Batch logs
curl -X POST http://localhost:8081/api/v1/telemetry/logs/batch \
  -H "X-API-Key: <your-api-key>" \
  -H "Content-Type: application/json" \
  -d '{"logs":[{"level":"INFO","message":"Request processed","timestamp":"2026-09-19T10:00:01Z"}]}'
```

### Via Fluent Bit (Agent)

Fluent Bit is pre-configured to tail `logs/*.log` and forward to the ingestion service:

```bash
# Append a log line (the agent picks it up automatically)
echo '{"level":"ERROR","message":"Disk full","timestamp":"2026-09-19T12:00:00Z"}' >> logs/dummy_app.log
```

---

## API Documentation

OpenAPI specs are auto-generated by SpringDoc and available at:

| Service | URL |
|---|---|
| **core-app** | http://localhost:8080/v3/api-docs |
| **ingestion-service** | http://localhost:8081/v3/api-docs |
| **Swagger UI (core)** | http://localhost:8080/swagger-ui.html |

Merged platform spec: [docs/openapi/platform-openapi.yaml](docs/openapi/platform-openapi.yaml)

---

## Running Tests

```bash
# All unit + integration tests (requires Docker for Testcontainers)
mvn clean test

# Specific module
mvn clean test -pl ingestion-service
mvn clean test -pl core-app
```

> **Note:** Integration tests use Testcontainers to spin up PostgreSQL, Kafka, Redis, and Elasticsearch automatically.

---

## Project Structure

```
Observability_Platform/
|-- platform-common/          # Shared DTOs and utilities
|-- platform-db/              # Flyway migrations
|-- ingestion-service/        # Log ingestion API
|-- indexer-worker/           # Kafka to Elasticsearch indexer
|-- analytics-engine/         # Alert rule evaluation
|-- alert-consumer/           # Alert persistence
|-- notification-dispatcher/  # Notification delivery
|-- core-app/                 # Main API + WebSocket
|-- frontend/                 # React dashboard
|-- fluent-bit/               # Fluent Bit agent config
|-- logs/                     # Log files mount (for Fluent Bit)
|-- scripts/                  # Dev/test scripts
|   |-- auth/                 # JWT and bcrypt generators
|   |-- db/                   # SQL seed scripts
|   |-- misc/                 # OpenAPI merge tools
|   +-- test/                 # Load testing and chaos scripts
|-- docs/                     # Documentation
|   |-- openapi/              # Merged OpenAPI specs
|   |-- SRS_v2.1_*.md         # Software Requirements Specification
|   |-- SDD_v1.1_*.md         # Software Design Document
|   +-- LLD_v1.1_*.md         # Low-Level Design
|-- docker-compose.yml        # Infrastructure services
|-- pom.xml                   # Maven parent POM
+-- run-all.ps1               # Quick-start script (Windows)
```

---

## Key Design Decisions

- **Event-Driven Architecture**: All inter-service communication goes through Kafka topics, ensuring loose coupling and horizontal scalability.
- **Idempotent Processing**: Duplicate log events and alerts are handled gracefully via business-key deduplication.
- **Dead Letter Queue (DLQ)**: Failed messages are routed to DLQ topics with retry tiers (1s, 5s, 30s) before final dead-lettering.
- **Zero-Trust Ingestion**: Every log request is authenticated via API Key, rate-limited per service, and validated before entering Kafka.
- **Real-time Dashboard**: STOMP over WebSocket pushes metrics, alerts, and service health updates to connected clients every 5 seconds.
- **Audit Trail**: Every mutation (create, update, delete) is atomically logged with user, IP, action, and result status.

---

## License

This project is for educational and portfolio purposes.
