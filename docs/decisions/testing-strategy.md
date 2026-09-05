# Testing Strategy Decision

**Date**: 2026-09-05
**Context**: Integration testing with Testcontainers caused stability issues (`Status 400 Bad Request` with ECI) and severe memory issues on Windows + Docker Desktop, preventing tests from running reliably locally without extensive manual daemon adjustments.
**Decision**: To ensure a stable and consistent test suite across the project, we are standardizing on using the **local infrastructure** provided by the existing `docker-compose.yml`.

## Requirements for Integration Tests
1. **Infra Requirement**: To run integration tests locally, developers must first run `docker-compose up -d` to spin up Elasticsearch, PostgreSQL, Kafka, Redis.
2. **No Testcontainers**: Integration tests will connect directly to `localhost` mapped ports, omitting the use of `@Testcontainers` annotations.
3. **Strict Data Cleanup (Idempotency)**: Since the tests use persistent shared infrastructure, they **MUST** leave the database state exactly as they found it.
   - Every integration test class must contain an `@AfterEach` method that manually deletes any rows it inserted (e.g., `DELETE FROM alert_rules WHERE rule_name LIKE 'test-%'`).
   - Test data IDs must be strictly namespaced (e.g., using `test-analytics-svc` instead of generic IDs) to avoid colliding with or accidentally deleting manually seeded development data (like the manual `payment-service` seeded in Phase 1).

**TODO**: LLD v1.1 mục 12.1 cần cập nhật lại bảng Test Suite cho khớp quyết định này.
