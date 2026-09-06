# Slice 5: Service Registry, Alert Rule Management & Audit Log (RBAC) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend `core-app` from JWT MVP into full RBAC (4 roles, SRS 13.2) and add three ADMIN/DEVOPS administration surfaces — Service Registry (register + API key, once-only plaintext), Alert Rule Management (full CRUD), and Alert Lifecycle (acknowledge/resolve state machine) — every mutation writing an Audit Log (BR-009, no exceptions), with an Audit Log read API scoped per role.

**Architecture:** One new shared hash utility + schema-contract fixture in `platform-common`; `ingestion-service` refactored to delegate its key hashing to that utility (single source of truth, zero coupling change); `core-app` gains new JPA entities/repositories, `@EnableMethodSecurity` + role-gated `@PreAuthorize` at controllers, `GlobalExceptionHandler` mappings for 403/404/409/400, and `@Transactional` service methods where audit SUCCESS rows join the caller's transaction while audit FAILED rows commit via `Propagation.REQUIRES_NEW` (survive the rollback). Audit rule: FAILED rows are written for domain-layer rejections (business-validation 400 and state-machine 409); 401/403/404 and pre-controller bean-validation are out of audit scope (documented).

**Tech Stack:** Spring Boot 3.3.4, Spring Security (`@EnableMethodSecurity`, `@PreAuthorize`), Spring Data JPA (PostgreSQL), Flyway (V1–V7 already present — no new migration), `platform-common` shared enums + `ApiKeyHashUtil` + `AlertColumnContract`, JUnit 5 + MockMvc + spring-security-test, docker-compose infra (no Testcontainers), Java 21, offline Maven `-o`.

**Spec:** SRS v2.1 §13.2 (RBAC matrix), §13.3 (API key life cycle); LLD v1.1 §2 (ServiceEntity, ServiceApiKeyEntity, AlertRuleEntity, AlertRuleChannelEntity, AlertEntity, AuditLogEntity), §3 (DTO conventions); this prompt's items 0–7 (mandatory pre-check, 3 locked decisions, 9 named tests). Locked decisions: (1) DEVOPS audit scope = `resource_target` in `services/%` | `alert-rules/%` only, ADMIN sees all; (2) `OPEN` ≡ alias of `TRIGGERED`, machine `TRIGGERED/OPEN → ACKNOWLEDGED → RESOLVED`, `resolve` only from `ACKNOWLEDGED`; (3) service `id` = slug derived from `name` (+ numeric suffix on collision).

## Key Pre-Code Findings (already verified — act on these, do NOT re-investigate)

1. **Hash contract (item 0):** `ingestion-service/.../security/ApiKeyValidator.java:42-62` hashes `SHA-256`, NO salt, UTF-8 → lowercase hex (`hashKey` + `bytesToHex`). There is NO shared utility — extract to `platform-common` `ApiKeyHashUtil` and refactor `ApiKeyValidator` to call it. Existing golden literal used in `IngestionIntegrationTest.setupDatabase()` (`1f8e8c97805e4ad56c611029fbba4c04dab40bf05d18c46655696357705cc136` = SHA-256 of `"test_key_123"`) must keep matching after the refactor — it becomes the cross-module consistency witness.
2. **`status = 'ACTIVE'` (FR-SVC-02) is ALREADY enforced in ingestion:** `ApiKeyValidator.java:30` (`AND s.status = 'ACTIVE'`) plus `revoked_at IS NULL` and expiry check (lines 28-29). **Do NOT patch ingestion for this** — only produce evidence in the completion report. Test #3 (`testDisabledService_ShouldRejectIngestion`) proves it live.
3. **`ROLE_` prefix is correct for `@PreAuthorize`:** `CustomUserDetailsService.java:31` builds `SimpleGrantedAuthority("ROLE_" + role.getName())`; DB role names are `ADMIN/DEVOPS/DEVELOPER/VIEWER` (V1/V6). JWT claim strips the prefix (`JwtTokenProvider.java:39`) but that does not affect the SecurityContext. Use `hasAuthority('ROLE_ADMIN')` etc.
4. **403 currently maps to 500 — must fix:** `GlobalExceptionHandler.java` has NO `AccessDeniedException` handler; the generic `Exception` handler (line 86) maps it to 500. Add `@EnableMethodSecurity` (SecurityConfig, line 17) + an `AccessDeniedException → 403` handler + `404`/`409`/`400-domain` handlers. The authentication entry point (SecurityConfig line 36-39) already sends raw 401 for unauthenticated — leave it.
5. **Schema is complete — NO new migration.** `services`/`service_api_keys` (V2, `key_prefix VARCHAR(12)`, `key_hash VARCHAR(255)`), `alert_rules` (V3 + V7 `condition_value VARCHAR(255)`), `alert_rule_channels` (V3 `ON DELETE CASCADE`), `alerts` (V4, 11 columns, `uq_alerts_business_key`), `audit_logs` (V5). `alert_rules.service_id` and `alert_rules.created_by` are nullable in DDL → map JPA `nullable = true` for `ddl-auto: validate`, enforce the business rule NOT NULL in DTO/service validation.
6. **`alerts` is mapped by TWO modules** (alert-consumer `AlertEntity` is a real JPA entity, `@Entity`/`@Table`, `AlertEntity.java:10-24`, module has `data-jpa`; core-app will add its own). They cannot import each other (sibling modules). Drift is caught by a shared `AlertColumnContract` fixture in `platform-common` (plain record) + one mirror contract test per module introspecting `information_schema.columns` (Task 2). `severity`/`status` are stored as `String` in BOTH entities; ALL writers use `AlertStatus.name()` / `Severity.name()` from platform-common (Task 6).
7. **Analytics rule cache latency:** `analytics-engine` `AlertRuleCacheService` refreshes on `@Scheduled(fixedRateString = "${analytics.rule.cache.refresh-rate:30000}")` → curated rule changes take effect within ≤30 s. This is BY DESIGN — document it in README (Task 8), it is not a bug.
8. **`core-app` tests run on docker-compose infra** (`application-test.yml`: PG `localhost:5432/observability`, `obs_user`/`obs_password`, flyway `classpath:db/migration`, `ddl-auto: validate`, ES `localhost:9200`). `CoreAppIntegrationTest` seeds `test_admin_user` + ES docs, cleans in `@AfterEach`. Sibling module tests cannot share code → a small `TestSeeds` helper lives under core-app `src/test`. Testcontainers deps in poms are unused (docker-compose is the infra) — leave them.
9. **Cosmetic anomaly to ignore:** `V6__seed_users_roles.sql` contains a commentary block with a placeholder bcrypt hash for `admin_user` (productionservice-ish, hash likely invalid). Tests do NOT use it (they seed their own users via `PasswordEncoder`). Do not rewrite V6 (Flyway checksum).

## Global Constraints

- No Testcontainers anywhere. Infra via `docker-compose up -d`; connections: PostgreSQL `localhost:5432/observability` (`obs_user`/`obs_password`), Kafka `localhost:9092`, Redis `localhost:6379`, Elasticsearch `localhost:9200`. Full reactor verify = `mvn clean test -o`. Java 21.
- All API responses use the existing `UnifiedResponse<T>` envelope (`core-app/.../api/dto/UnifiedResponse.java`). Error codes: 401 `AUTHENTICATION_FAILED`, 400 `VALIDATION_ERROR`, 403 `ACCESS_DENIED`, 404 `NOT_FOUND`, 409 `CONFLICT`, 500 `INTERNAL_SERVER_ERROR`.
- RBAC exactly per SRS 13.2, via `@PreAuthorize` on controller methods: READ logs/alerts = any authenticated; ACKNOWLEDGE = `ROLE_ADMIN,ROLE_DEVOPS,ROLE_DEVELOPER`; RESOLVE & alert-rule CRUD & services GET = `ROLE_ADMIN,ROLE_DEVOPS`; services mutate = `ROLE_ADMIN`; audit GET = `ROLE_ADMIN` (all) / `ROLE_DEVOPS` (operational scope only). Wrong role ⇒ 403 (never 401, never 500).
- Audit log (BR-009) MANDATORY for every mutating op in all three surfaces, with the chosen mechanism: `recordSuccess` = `@Transactional` (REQUIRED, joins caller's tx → atomic rollback with the mutation); `recordFailure` = `@Transactional(Propagation.REQUIRES_NEW)` (independent commit, survives the outer rollback) and must be called BEFORE the `ConflictException`/`BadRequestException` is thrown. FAILED rows are written only for domain-layer rejections (service-layer business validation → 400; state-machine conflicts → 409). NOT written for 401/403/404 or pre-controller `@Valid` bean-validation failures (documented in README).
- Audit rows: `username` = JWT `sub` snapshot (from `SecurityContextHolder` principal), `ip_address` = `request.getRemoteAddr()` unless `app.audit.trust-forwarded=true` (then first `X-Forwarded-For` entry), `result_status` = `SUCCESS`/`FAILED`. Never store the plaintext API key; store `key_prefix` (12 chars) + SHA-256 `key_hash` only.
- No new Flyway migration. No JPA cascade beyond `AlertRuleEntity.channels` (`cascade=ALL, orphanRemoval=true` — the ONLY cascade) and table-level `ON DELETE CASCADE` in DDL.
- Service `id` = slug of `name` (`lower(name).replaceAll("[^a-z0-9]+","-")`, collapse, trim `-`), suffixed `-2`, `-3`, … on collision. API key = `sk_` + 32 random bytes Base64-URL (no padding); `keyPrefix = plain.substring(0,12)`.
- State machine (decision 2): initial states `TRIGGERED`|`OPEN` ≡ alias; `acknowledge` valid only from `TRIGGERED`/`OPEN` → `ACKNOWLEDGED` (+`acknowledged_at`); `resolve` valid only from `ACKNOWLEDGED` → `RESOLVED` (+`resolved_at`); any transition against a `RESOLVED` alert → explicit 409 error (never silent overwrite); any other invalid transition → 409.
- PATTERN_MATCH rules REQUIRE non-blank `condition_value`; `threshold_value`/`window_seconds` defaulted server-side (`1`/`60`) when null. ERROR_SPIKE rules REQUIRE `threshold_value > 0` and `window_seconds > 0`. `notificationChannels` are replaced wholesale on update (orphanRemoval deletes removed rows).
- Integration tests use namespaced IDs (`test-*`) and clean up ONLY rows they created, via `@AfterEach`. Never run a bare `DELETE FROM` without `WHERE`.
- Run Maven offline (`-o`). Commit after each task's green run, one logical commit per task (`git add` only intended files; never commit secrets — plaintext API keys exist only inside test bodies).

---

## File Structure & Responsibilities

**Modified (existing):**
- `ingestion-service/src/main/java/com/thinhbui303/observability/ingestion/security/ApiKeyValidator.java` — delegate `hashKey`/`bytesToHex` to `platform-common.ApiKeyHashUtil`; delete private duplicate logic.
- `ingestion-service/src/test/java/com/thinhbui303/observability/ingestion/IngestionIntegrationTest.java` — seed `test_key_123`'s hash via `ApiKeyHashUtil.hash(...)` (keep the golden literal as a comment), add `testHashConsistency_CoreAppAndIngestionService_ShouldMatch` and `testDisabledService_ShouldRejectIngestion`.
- `core-app/src/main/java/com/thinhbui303/observability/core/security/SecurityConfig.java` — add `@EnableMethodSecurity`.
- `core-app/src/main/java/com/thinhbui303/observability/core/api/GlobalExceptionHandler.java` — add handlers: `AccessDeniedException→403`, `NotFoundException→404`, `ConflictException→409`, `BadRequestException→400`.
- `core-app/src/main/resources/application.yml` — add `spring.jpa` section: `hibernate.ddl-auto: validate`, `open-in-view: false`; add `app.audit.trust-forwarded: false`.
- `core-app/src/test/java/com/thinhbui303/observability/core/CoreAppIntegrationTest.java` — add `testSearchLogs_AsViewer_ShouldReturn200` (regression: method-security must not tighten `/logs`).
- `alert-consumer/src/test/java/com/thinhbui303/observability/alert/AlertColumnContractVsConsumerEntityTest.java` — NEW mirror contract test (Task 2).

**Created (platform-common):**
- `platform-common/src/main/java/com/thinhbui303/observability/common/ApiKeyHashUtil.java` — static `hash(String): String`, SHA-256/UTF-8/hex-lowercase.
- `platform-common/src/main/java/com/thinhbui303/observability/common/AlertColumnContract.java` — `AlertColumn` record + `ALERTS` list (name, PG `data_type`, nullable).
- `platform-common/src/test/java/com/thinhbui303/observability/common/ApiKeyHashUtilTest.java` — golden vectors.

**Created (core-app main):**
- `domain/ServiceEntity.java`, `domain/ServiceApiKeyEntity.java`, `domain/AlertRuleEntity.java`, `domain/AlertRuleChannelEntity.java`, `domain/AlertEntity.java`, `domain/AuditLogEntity.java`
- `repository/ServiceRepository.java`, `repository/ServiceApiKeyRepository.java`, `repository/AlertRuleRepository.java`, `repository/AlertRepository.java`, `repository/AuditLogRepository.java`
- `api/exception/ConflictException.java`, `BadRequestException.java`, `NotFoundException.java`
- `api/dto/` — `CreateServiceRequest.java`, `UpdateServiceStatusRequest.java`, `ServiceCreateResponse.java`, `ServiceResponse.java`, `ChannelRequest.java`, `CreateAlertRuleRequest.java`, `UpdateAlertRuleRequest.java`, `SetAlertRuleEnabledRequest.java`, `AlertRuleResponse.java`, `AlertResponse.java`, `AuditLogResponse.java`
- `service/ApiKeyGenerator.java` (final class, static `generate()`), `service/SlugBuilder.java` (static `slug(String)`), `service/AuditRecord.java` (record + `toEntity()`), `service/AuditLogService.java`, `service/OperationContext.java` (username + ip resolution), `service/ServiceManagementService.java`, `service/AlertRuleManagementService.java`, `service/AlertLifecycleService.java`
- `api/ServiceController.java`, `api/AlertRuleController.java`, `api/AlertController.java`, `api/AuditLogController.java`

**Created (core-app test):**
- `test/java/com/thinhbui303/observability/core/TestSeeds.java` (shared helpers), `ServiceRegistryIntegrationTest.java`, `AlertRuleManagementIntegrationTest.java`, `AuditAtomicityIntegrationTest.java` (`@SpyBean` repository), `AlertLifecycleIntegrationTest.java`, `AuditLogReadIntegrationTest.java`

---

## Interfaces (cross-task contracts)

- `public static String ApiKeyHashUtil.hash(String rawKey)` — sha256(utf8(raw))+hex(lower). T1 → consumed T2..T8.
- `public record AlertColumn(String name, String pgType, boolean nullable)` + `public static final List<AlertColumn> AlertColumnContract.ALERTS` — T1 → consumed T2 (both modules' contract tests).
- `public final class ApiKeyGenerator { public static String generate(); }` — returns `sk_` + 32B Base64URL. T4 → T4 tests.
- `public final class SlugBuilder { public static String slug(String name); }` — T4 → T4/T5.
- `public record AuditRecord(String username, String action, String resourceTarget, String ipAddress, String resultStatus) { public AuditLogEntity toEntity(); public static AuditRecord of(...); }` — T4 → T4..T7.
- `public class AuditLogService { @Transactional void recordSuccess(AuditRecord r); @Transactional(REQUIRES_NEW) void recordFailure(AuditRecord r); }` — T4 → T4..T7.
- `public class OperationContext { String currentUsername(); String resolveIp(HttpServletRequest req); }` — T4 → T4..T7.
- Exceptions: `class ConflictException extends RuntimeException` (code, message), `class NotFoundException` , `class BadRequestException` — T3 → T4..T7.
- Service signatures:
  - `ServiceManagementService`: `ServiceCreateResponse create(CreateServiceRequest, String username, String ip)`, `List<ServiceResponse> list()`, `ServiceResponse updateStatus(String id, UpdateServiceStatusRequest, String username, String ip)`.
  - `AlertRuleManagementService`: `AlertRuleResponse create(CreateAlertRuleRequest, String username, String ip)`, `AlertRuleResponse update(Long id, UpdateAlertRuleRequest, String username, String ip)`, `AlertRuleResponse setEnabled(Long id, SetAlertRuleEnabledRequest, String username, String ip)`, `void delete(Long id, String username, String ip)`, `List<AlertRuleResponse> list(String serviceId, String environment)`.
  - `AlertLifecycleService`: `AlertResponse acknowledge(String id, String username, String ip)`, `AlertResponse resolve(String id, String username, String ip)`, `List<AlertResponse> list(String serviceId, String environment, String status)`.

---

### Task 1: Shared Hash Utility & Schema Contract (platform-common) + ingestion refactor

**Files:**
- Create: `platform-common/src/main/java/com/thinhbui303/observability/common/ApiKeyHashUtil.java`
- Create: `platform-common/src/main/java/com/thinhbui303/observability/common/AlertColumnContract.java`
- Test: `platform-common/src/test/java/com/thinhbui303/observability/common/ApiKeyHashUtilTest.java`
- Modify: `ingestion-service/src/main/java/com/thinhbui303/observability/ingestion/security/ApiKeyValidator.java`
- Modify: `ingestion-service/src/test/java/com/thinhbui303/observability/ingestion/IngestionIntegrationTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `ApiKeyHashUtil.hash(String)`, `AlertColumn(String, String, boolean)`, `AlertColumnContract.ALERTS`.

- [ ] **Step 1: Write the failing platform-common test**

```java
package com.thinhbui303.observability.common;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyHashUtilTest {

    @Test
    void hash_ShouldReturnSha256LowercaseHex_NoSalt() {
        assertThat(ApiKeyHashUtil.hash("abc")).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(ApiKeyHashUtil.hash("test_key_123")).isEqualTo(
                "1f8e8c97805e4ad56c611029fbba4c04dab40bf05d18c46655696357705cc136");
    }
}
```

- [ ] **Step 2: Run platform-common test to verify it fails (not compiled)**

Run: `mvn -o -pl platform-common test`
Expected: FAIL — `ApiKeyHashUtil` does not exist.

- [ ] **Step 3: Implement `ApiKeyHashUtil`**

```java
package com.thinhbui303.observability.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ApiKeyHashUtil {

    private ApiKeyHashUtil() {
    }

    public static String hash(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
```

- [ ] **Step 4: Run platform-common test**
Run: `mvn -o -pl platform-common test`
Expected: PASS (2 assertions).

- [ ] **Step 5: Refactor `ApiKeyValidator` to delegate to `ApiKeyHashUtil`**

Replace the bodies of `hashKey(String)` and `bytesToHex(byte[])` in `ApiKeyValidator.java`:

```java
private String hashKey(String rawKey) {
    return ApiKeyHashUtil.hash(rawKey);
}
```

Delete `bytesToHex`. Remove now-unused imports (`StandardCharsets`, `MessageDigest`, `NoSuchAlgorithmException`), add `import com.thinhbui303.observability.common.ApiKeyHashUtil;`. Keep the `@Cacheable`, SQL, and `s.status = 'ACTIVE'` filter (lines 21-40) untouched.

- [ ] **Step 6: Switch ingestion integration-test seeding to the shared util**

Modify `IngestionIntegrationTest.setupDatabase()` (lines 95-104): replace the hardcoded hash literal `"1f8e8c97805e4ad56c611029fbba4c04dab40bf05d18c46655696357705cc136"` with `ApiKeyHashUtil.hash("test_key_123")`, keeping the hash comment above the insert. Also delete the disabled-service rows before insert (idempotent seeding):

```java
if (!dbInitialized) {
    jdbcTemplate.update("DELETE FROM service_api_keys WHERE service_id = ?", "test-service");
    jdbcTemplate.update("DELETE FROM services WHERE id = ?", "test-service");
    jdbcTemplate.update("DELETE FROM service_api_keys WHERE service_id = ?", "test-disabled-service");
    jdbcTemplate.update("DELETE FROM services WHERE id = ?", "test-disabled-service");

    // Key: test_key_123 -> SHA-256 = 1f8e8c97805e4ad56c611029fbba4c04dab40bf05d18c46655696357705cc136
    jdbcTemplate.update("INSERT INTO services (id, name, team_owner, environment) VALUES (?, ?, ?, ?) ON CONFLICT (id) DO NOTHING", "test-service", "Test Service", "backend-team", "production");
    jdbcTemplate.update("INSERT INTO service_api_keys (service_id, key_prefix, key_hash, created_at) VALUES (?, ?, ?, now())",
            "test-service", "test_key", ApiKeyHashUtil.hash("test_key_123"));

    // Disabled service: same key format, DIFFERENT id, status DISABLED
    jdbcTemplate.update("INSERT INTO services (id, name, team_owner, environment, status) VALUES (?, ?, ?, ?, 'DISABLED') ON CONFLICT (id) DO NOTHING",
            "test-disabled-service", "Test Disabled Service", "backend-team", "production");
    jdbcTemplate.update("INSERT INTO service_api_keys (service_id, key_prefix, key_hash, created_at) VALUES (?, ?, ?, now())",
            "test-disabled-service", "test_disab", ApiKeyHashUtil.hash("test_disabled_key_123"));
    dbInitialized = true;
}
```

Add import `com.thinhbui303.observability.common.ApiKeyHashUtil;`.

- [ ] **Step 7: Add the two new named ingestion tests**

Add to `IngestionIntegrationTest`:

```java
@Test
void testHashConsistency_CoreAppAndIngestionService_ShouldMatch() throws Exception {
    // Hash produced by the shared platform-common util (the same util core-app uses to
    // generate a key) is accepted by ingestion's ApiKeyValidator for the seeded key.
    assertThat(ApiKeyHashUtil.hash("test_key_123")).isEqualTo(
            "1f8e8c97805e4ad56c611029fbba4c04dab40bf05d18c46655696357705cc136");

    String payload = """
            {
                "timestamp": "2026-09-06T10:00:00Z",
                "level": "INFO",
                "message": "Hash consistency"
            }
            """;
    mockMvc.perform(post("/api/v1/telemetry/logs")
                    .header("X-API-Key", "test_key_123")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload))
            .andExpect(status().isAccepted());
}

@Test
void testDisabledService_ShouldRejectIngestion() throws Exception {
    String payload = """
            {
                "timestamp": "2026-09-06T10:00:00Z",
                "level": "INFO",
                "message": "Disabled service rejected"
            }
            """;
    // key hash is valid (test_disabled_key_123 -> stored via ApiKeyHashUtil) but services.status = 'DISABLED'
    mockMvc.perform(post("/api/v1/telemetry/logs")
                    .header("X-API-Key", "test_disabled_key_123")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload))
            .andExpect(status().isUnauthorized());
}
```

`assertThat` and `post`/`status` imports already exist in this file.

- [ ] **Step 8: Add `AlertColumnContract` to platform-common**

```java
package com.thinhbui303.observability.common;

import java.util.List;

public record AlertColumn(String name, String pgType, boolean nullable) {
}

public final class AlertColumnContract {

    private AlertColumnContract() {
    }

    // Single source of truth for V4__create_alerts.sql. PG data_type strings are the exact
    // values returned by information_schema.columns.data_type.
    public static final List<AlertColumn> ALERTS = List.of(
            new AlertColumn("id", "character varying", false),
            new AlertColumn("rule_id", "bigint", true),
            new AlertColumn("service_id", "character varying", true),
            new AlertColumn("environment", "character varying", false),
            new AlertColumn("window_start", "timestamp with time zone", false),
            new AlertColumn("severity", "character varying", false),
            new AlertColumn("status", "character varying", false),
            new AlertColumn("triggered_at", "timestamp with time zone", false),
            new AlertColumn("acknowledged_at", "timestamp with time zone", true),
            new AlertColumn("resolved_at", "timestamp with time zone", true),
            new AlertColumn("occurrence_count", "integer", false)
    );
}
```

- [ ] **Step 9: Run both module test suites**

Run: `mvn -o -pl platform-common,ingestion-service test`
Expected: PASS. Ingestion = 7 tests (5 existing + 2 new, all green with the util-seeded hashes). Platform-common = 1 test.

- [ ] **Step 10: Commit**

```bash
git add platform-common ingestion-service
git commit -m "feat(platform-common): extract shared ApiKeyHashUtil and AlertColumnContract; refactor ingestion hashing"
```

---

### Task 2: core-app JPA entities + repositories + dual-module schema contract tests

**Files:**
- Create: `core-app/src/main/java/com/thinhbui303/observability/core/domain/ServiceEntity.java`
- Create: `core-app/src/main/java/com/thinhbui303/observability/core/domain/ServiceApiKeyEntity.java`
- Create: `core-app/src/main/java/com/thinhbui303/observability/core/domain/AlertRuleEntity.java`
- Create: `core-app/src/main/java/com/thinhbui303/observability/core/domain/AlertRuleChannelEntity.java`
- Create: `core-app/src/main/java/com/thinhbui303/observability/core/domain/AlertEntity.java`
- Create: `core-app/src/main/java/com/thinhbui303/observability/core/domain/AuditLogEntity.java`
- Create: `core-app/src/main/java/com/thinhbui303/observability/core/repository/ServiceRepository.java`, `ServiceApiKeyRepository.java`, `AlertRuleRepository.java`, `AlertRepository.java`, `AuditLogRepository.java`
- Modify: `core-app/src/main/resources/application.yml`
- Test: `core-app/src/test/java/com/thinhbui303/observability/core/AlertColumnContractVsCoreEntityTest.java`
- Test: `alert-consumer/src/test/java/com/thinhbui303/observability/alert/AlertColumnContractVsConsumerEntityTest.java`

**Interfaces:**
- Consumes: `AlertColumnContract.ALERTS` (T1).
- Produces: the 6 entities (field names per spec below) + 5 repositories used by T4-T7.

- [ ] **Step 1: Write the failing core-app schema-contract test**

```java
package com.thinhbui303.observability.core;

import com.thinhbui303.observability.common.AlertColumn;
import com.thinhbui303.observability.common.AlertColumnContract;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AlertColumnContractVsCoreEntityTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void alertsTable_ShouldMatchSharedContract_andCoreEntityMapping() {
        List<Object[]> actual = jdbcTemplate.query(
                "SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_name = 'alerts' ORDER BY ordinal_position",
                rs -> {
                    java.util.ArrayList<Object[]> rows = new java.util.ArrayList<>();
                    while (rs.next()) {
                        rows.add(new Object[]{rs.getString("column_name"), rs.getString("data_type"), rs.getString("is_nullable")});
                    }
                    return rows;
                });

        List<AlertColumn> contract = AlertColumnContract.ALERTS;
        assertThat(actual).hasSize(contract.size());
        for (int i = 0; i < contract.size(); i++) {
            AlertColumn c = contract.get(i);
            assertThat(actual.get(i)[0]).as("column name #%d", i).isEqualTo(c.name());
            assertThat(actual.get(i)[1]).as("type of %s", c.name()).isEqualTo(c.pgType());
            assertThat(actual.get(i)[2]).as("nullable of %s", c.name()).isEqualTo(c.nullable() ? "YES" : "NO");
        }
    }
}
```

- [ ] **Step 2: Run core-app contract test**
Run: `mvn -o -pl core-app test -Dtest=AlertColumnContractVsCoreEntityTest`
Expected: FAIL — `core-app` does not yet compile against entity classes? It compiles in the next steps; first run fails because `CoreAppApplication` context loads with entities undeclared. (Test requires infra up.) Verify it FAILS as the TDD step.

- [ ] **Step 3: Implement the six entities**

`ServiceEntity.java`:

```java
package com.thinhbui303.observability.core.domain;

import com.thinhbui303.observability.common.ServiceStatus;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "services")
public class ServiceEntity {

    @Id
    @Column(length = 50)
    private String id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "team_owner", nullable = false, length = 50)
    private String teamOwner;

    @Column(nullable = false, length = 20)
    private String environment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ServiceStatus status = ServiceStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ServiceEntity() {}
    // Standard getters/setters (codebase style — see UserEntity.java)
    public String getId() { return id; } public void setId(String id) { this.id = id; }
    public String getName() { return name; } public void setName(String name) { this.name = name; }
    public String getTeamOwner() { return teamOwner; } public void setTeamOwner(String teamOwner) { this.teamOwner = teamOwner; }
    public String getEnvironment() { return environment; } public void setEnvironment(String environment) { this.environment = environment; }
    public ServiceStatus getStatus() { return status; } public void setStatus(ServiceStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

`ServiceApiKeyEntity.java`:

```java
package com.thinhbui303.observability.core.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "service_api_keys")
public class ServiceApiKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id", nullable = false)
    private ServiceEntity service;

    @Column(name = "key_prefix", nullable = false, length = 12)
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false, length = 255)
    private String keyHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    public ServiceApiKeyEntity() {}
    // Standard getters/setters
    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public ServiceEntity getService() { return service; } public void setService(ServiceEntity service) { this.service = service; }
    public String getKeyPrefix() { return keyPrefix; } public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    public String getKeyHash() { return keyHash; } public void setKeyHash(String keyHash) { this.keyHash = keyHash; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getExpiresAt() { return expiresAt; } public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getRevokedAt() { return revokedAt; } public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public Instant getLastUsedAt() { return lastUsedAt; } public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}
```

`AlertRuleChannelEntity.java`:

```java
package com.thinhbui303.observability.core.domain;

import com.thinhbui303.observability.common.ChannelType;
import jakarta.persistence.*;

@Entity
@Table(name = "alert_rule_channels")
public class AlertRuleChannelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "alert_rule_id", nullable = false)
    private AlertRuleEntity alertRule;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false, length = 20)
    private ChannelType channelType;

    @Column(length = 255)
    private String target;

    @Column(nullable = false)
    private boolean enabled = true;

    public AlertRuleChannelEntity() {}
    // Standard getters/setters
    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public AlertRuleEntity getAlertRule() { return alertRule; } public void setAlertRule(AlertRuleEntity alertRule) { this.alertRule = alertRule; }
    public ChannelType getChannelType() { return channelType; } public void setChannelType(ChannelType channelType) { this.channelType = channelType; }
    public String getTarget() { return target; } public void setTarget(String target) { this.target = target; }
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
```

`AlertRuleEntity.java`:

```java
package com.thinhbui303.observability.core.domain;

import com.thinhbui303.observability.common.ConditionType;
import com.thinhbui303.observability.common.Severity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "alert_rules")
public class AlertRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_name", nullable = false, length = 100)
    private String ruleName;

    // LLD: object relation ONLY — no parallel raw serviceId string
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id")
    private ServiceEntity service;

    @Column(nullable = false, length = 20)
    private String environment;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_type", nullable = false, length = 50)
    private ConditionType conditionType;

    @Column(name = "threshold_value", nullable = false)
    private Integer thresholdValue;

    @Column(name = "window_seconds", nullable = false)
    private Integer windowSeconds;

    @Column(name = "condition_value", length = 255)
    private String conditionValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Severity severity;

    @Column(name = "is_enabled", nullable = false)
    private boolean isEnabled = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private UserEntity createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // The ONLY cascade in the model: channels live & die with their rule
    @OneToMany(mappedBy = "alertRule", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AlertRuleChannelEntity> channels = new ArrayList<>();

    public AlertRuleEntity() {}
    public void addChannel(AlertRuleChannelEntity c) { c.setAlertRule(this); channels.add(c); }
    // Standard getters/setters
    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getRuleName() { return ruleName; } public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    public ServiceEntity getService() { return service; } public void setService(ServiceEntity service) { this.service = service; }
    public String getEnvironment() { return environment; } public void setEnvironment(String environment) { this.environment = environment; }
    public ConditionType getConditionType() { return conditionType; } public void setConditionType(ConditionType conditionType) { this.conditionType = conditionType; }
    public Integer getThresholdValue() { return thresholdValue; } public void setThresholdValue(Integer thresholdValue) { this.thresholdValue = thresholdValue; }
    public Integer getWindowSeconds() { return windowSeconds; } public void setWindowSeconds(Integer windowSeconds) { this.windowSeconds = windowSeconds; }
    public String getConditionValue() { return conditionValue; } public void setConditionValue(String conditionValue) { this.conditionValue = conditionValue; }
    public Severity getSeverity() { return severity; } public void setSeverity(Severity severity) { this.severity = severity; }
    public boolean isEnabled() { return isEnabled; } public void setEnabled(boolean enabled) { isEnabled = enabled; }
    public UserEntity getCreatedBy() { return createdBy; } public void setCreatedBy(UserEntity createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public List<AlertRuleChannelEntity> getChannels() { return channels; } public void setChannels(List<AlertRuleChannelEntity> channels) { this.channels = channels; }
}
```

`AlertEntity.java` (core-app read/write view — String severity/status per decision 2, writers use enum `.name()`):

```java
package com.thinhbui303.observability.core.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "alerts")
public class AlertEntity {

    @Id
    @Column(length = 50)
    private String id;

    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "service_id", length = 50)
    private String serviceId;

    @Column(nullable = false, length = 20)
    private String environment;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(nullable = false, length = 20)
    private String severity;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "triggered_at", nullable = false)
    private Instant triggeredAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "occurrence_count", nullable = false)
    private int occurrenceCount;

    public AlertEntity() {}
    // Standard getters/setters (see alert-consumer AlertEntity for the field set)
    public String getId() { return id; } public void setId(String id) { this.id = id; }
    public Long getRuleId() { return ruleId; } public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public String getServiceId() { return serviceId; } public void setServiceId(String serviceId) { this.serviceId = serviceId; }
    public String getEnvironment() { return environment; } public void setEnvironment(String environment) { this.environment = environment; }
    public Instant getWindowStart() { return windowStart; } public void setWindowStart(Instant windowStart) { this.windowStart = windowStart; }
    public String getSeverity() { return severity; } public void setSeverity(String severity) { this.severity = severity; }
    public String getStatus() { return status; } public void setStatus(String status) { this.status = status; }
    public Instant getTriggeredAt() { return triggeredAt; } public void setTriggeredAt(Instant triggeredAt) { this.triggeredAt = triggeredAt; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; } public void setAcknowledgedAt(Instant acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }
    public Instant getResolvedAt() { return resolvedAt; } public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public int getOccurrenceCount() { return occurrenceCount; } public void setOccurrenceCount(int occurrenceCount) { this.occurrenceCount = occurrenceCount; }
}
```

`AuditLogEntity.java`:

```java
package com.thinhbui303.observability.core.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_logs")
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(name = "resource_target", nullable = false, length = 100)
    private String resourceTarget;

    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    @Column(name = "result_status", nullable = false, length = 20)
    private String resultStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public AuditLogEntity() {}
    // Standard getters/setters
    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; } public void setUsername(String username) { this.username = username; }
    public String getAction() { return action; } public void setAction(String action) { this.action = action; }
    public String getResourceTarget() { return resourceTarget; } public void setResourceTarget(String resourceTarget) { this.resourceTarget = resourceTarget; }
    public String getIpAddress() { return ipAddress; } public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getResultStatus() { return resultStatus; } public void setResultStatus(String resultStatus) { this.resultStatus = resultStatus; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 4: Implement the five repositories**

```java
package com.thinhbui303.observability.core.repository;

import com.thinhbui303.observability.core.domain.ServiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ServiceRepository extends JpaRepository<ServiceEntity, String> {
}
```
```java
package com.thinhbui303.observability.core.repository;

import com.thinhbui303.observability.core.domain.ServiceApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ServiceApiKeyRepository extends JpaRepository<ServiceApiKeyEntity, Long> {
}
```
```java
package com.thinhbui303.observability.core.repository;

import com.thinhbui303.observability.core.domain.AlertRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlertRuleRepository extends JpaRepository<AlertRuleEntity, Long> {

    @Query("SELECT r FROM AlertRuleEntity r WHERE (:serviceId IS NULL OR r.service.id = :serviceId) " +
           "AND (:environment IS NULL OR r.environment = :environment)")
    List<AlertRuleEntity> search(@Param("serviceId") String serviceId, @Param("environment") String environment);
}
```
```java
package com.thinhbui303.observability.core.repository;

import com.thinhbui303.observability.core.domain.AlertEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlertRepository extends JpaRepository<AlertEntity, String> {

    @Query("SELECT a FROM AlertEntity a WHERE (:serviceId IS NULL OR a.serviceId = :serviceId) " +
           "AND (:environment IS NULL OR a.environment = :environment) " +
           "AND (:status IS NULL OR a.status = :status)")
    List<AlertEntity> search(@Param("serviceId") String serviceId,
                             @Param("environment") String environment,
                             @Param("status") String status);
}
```
```java
package com.thinhbui303.observability.core.repository;

import com.thinhbui303.observability.core.domain.AuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    @Query("SELECT a FROM AuditLogEntity a " +
           "WHERE (:action IS NULL OR a.action = :action) " +
           "AND (:username IS NULL OR a.username = :username) " +
           "AND (:resourceTarget IS NULL OR a.resourceTarget LIKE CONCAT('%', :resourceTarget, '%')) " +
           "ORDER BY a.id DESC")
    List<AuditLogEntity> search(@Param("action") String action,
                                @Param("username") String username,
                                @Param("resourceTarget") String resourceTarget);
}
```

- [ ] **Step 5: Update `application.yml` (JPA alignment + trust-forwarded flag)**

Append under `spring:` (after `flyway`):

```yaml
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
```
and at the end:
```yaml
app:
  audit:
    trust-forwarded: false
```

- [ ] **Step 6: Run core-app tests (context boots + contract test)**
Run: `mvn -o -pl core-app test`
Expected: PASS (existing 8 CoreAppIntegrationTest + the new contract test). This proves `ddl-auto: validate` accepts every new entity mapping against the real V1-V7 schema.

- [ ] **Step 7: Add the mirror contract test in alert-consumer**

`alert-consumer/src/test/java/com/thinhbui303/observability/alert/AlertColumnContractVsConsumerEntityTest.java` — same assertion body as Step 1 (package `com.thinhbui303.observability.alert`, class name `AlertColumnContractVsConsumerEntityTest`, uses `@SpringBootTest` + `@ActiveProfiles("test")` + `JdbcTemplate`). For the alert-consumer context, use ONLY the default application.yml datasource (do not add a new application-test.yml). Assert equality between `AlertColumnContract.ALERTS` and `information_schema` (identical `SELECT` as Step 1).

Why this catches entity drift in BOTH modules: `alert-consumer/src/main/resources/application.yml:9-11` already sets `spring.jpa.hibernate.ddl-auto: validate` (its test profile `application-test.yml` only overrides Kafka props, so validation still runs at `@SpringBootTest` boot) — same as core-app. Hibernate therefore fails the context startup if the module's JPA entity deviates from the DB schema, and this test proves schema == `AlertColumnContract`; composing the two ⇒ entity == contract. No config change needed here.

- [ ] **Step 8: Run alert-consumer module tests**
Run: `mvn -o -pl alert-consumer test`
Expected: PASS (3 existing integration tests + the new contract test). This is the field-by-field drift check between the two modules' mappings through one shared fixture.

- [ ] **Step 9: Commit**

```bash
git add core-app alert-consumer
git commit -m "feat(core-app): add entities and repositories; bound alert table to shared AlertColumnContract in both modules"
```

---

### Task 3: Method security + exception handlers + auth regression

**Files:**
- Modify: `core-app/src/main/java/com/thinhbui303/observability/core/security/SecurityConfig.java`
- Create: `core-app/src/main/java/com/thinhbui303/observability/core/api/exception/ConflictException.java`, `BadRequestException.java`, `NotFoundException.java`
- Modify: `core-app/src/main/java/com/thinhbui303/observability/core/api/GlobalExceptionHandler.java`
- Modify: `core-app/src/test/java/com/thinhbui303/observability/core/CoreAppIntegrationTest.java`

**Interfaces:**
- Consumes: nothing from T1/T2 (pure security plumbing).
- Produces: the three exceptions, the four new handlers, `@EnableMethodSecurity`, and the VIEWER regression test.

- [ ] **Step 1: Write the failing regression test (VIEWER must keep reading logs)**

Add to `CoreAppIntegrationTest`, plus a `seedViewer` helper invoked from `setUp()`:

In `setUp()` add:

```java
if (userRepository.findByUsername("test_viewer_user").isEmpty()) {
    UserEntity viewer = new UserEntity();
    viewer.setUsername("test_viewer_user");
    viewer.setPasswordHash(passwordEncoder.encode("password123"));
    UserEntity savedViewer = userRepository.save(viewer);
    jdbcTemplate.execute("INSERT INTO roles (name) VALUES ('VIEWER') ON CONFLICT (name) DO NOTHING");
    jdbcTemplate.execute("INSERT INTO user_roles (user_id, role_id) VALUES (" + savedViewer.getId() + ", (SELECT id FROM roles WHERE name = 'VIEWER'))");
}
```
In `tearDown()` add:

```java
jdbcTemplate.execute("DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE username IN ('test_viewer_user'))");
jdbcTemplate.execute("DELETE FROM users WHERE username = 'test_viewer_user'");
```
New test:

```java
@Test
void testSearchLogs_AsViewer_ShouldReturn200() throws Exception {
    LoginRequest loginRequest = new LoginRequest();
    loginRequest.setUsername("test_viewer_user");
    loginRequest.setPassword("password123");

    MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(loginRequest)))
            .andExpect(status().isOk())
            .andReturn();
    String jwt = login.getResponse().getContentAsString();
    int start = jwt.indexOf("\"accessToken\":\"") + 15;
    int end = jwt.indexOf("\"", start);
    String token = jwt.substring(start, end);

    mockMvc.perform(get("/api/v1/logs?service=payment-service")
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"));
}
```
(If preferred, reuse the existing `getValidJwt()` by making it accept a username — but keep the helper inline for isolation.)

- [ ] **Step 2: Run test to verify it fails**
Run: `mvn -o -pl core-app test -Dtest=CoreAppIntegrationTest#testSearchLogs_AsViewer_ShouldReturn200`
Expected: FAIL — viewer user is not seeded in the test yet is expected 200. (attest TDD: test written first.)

- [ ] **Step 3: Enable method security**

In `SecurityConfig.java` add import `org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;` and the annotation above `@Configuration`:

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
```

- [ ] **Step 4: Add the three exceptions**

```java
package com.thinhbui303.observability.core.api.exception;

public class ConflictException extends RuntimeException {
    private final String code;
    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }
    public String getCode() { return code; }
}
```
```java
package com.thinhbui303.observability.core.api.exception;

public class NotFoundException extends RuntimeException {
    private final String code;
    public NotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }
    public String getCode() { return code; }
}
```
```java
package com.thinhbui303.observability.core.api.exception;

public class BadRequestException extends RuntimeException {
    private final String code;
    public BadRequestException(String code, String message) {
        super(message);
        this.code = code;
    }
    public String getCode() { return code; }
}
```

- [ ] **Step 5: Add the four handlers to `GlobalExceptionHandler`**

Add imports: `org.springframework.security.access.AccessDeniedException` (the Spring one), `com.thinhbui303.observability.core.api.exception.*`. Insert before the generic `@ExceptionHandler(Exception.class)`:

```java
@ExceptionHandler(AccessDeniedException.class)
public ResponseEntity<UnifiedResponse<Void>> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
    UnifiedResponse<Void> response = new UnifiedResponse<>(
            "ACCESS_DENIED",
            "You do not have permission to perform this action",
            DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            request.getRequestURI()
    );
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
}

@ExceptionHandler(NotFoundException.class)
public ResponseEntity<UnifiedResponse<Void>> handleNotFound(NotFoundException ex, HttpServletRequest request) {
    UnifiedResponse<Void> response = new UnifiedResponse<>(
            "NOT_FOUND",
            ex.getMessage(),
            DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            request.getRequestURI()
    );
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
}

@ExceptionHandler(ConflictException.class)
public ResponseEntity<UnifiedResponse<Void>> handleConflict(ConflictException ex, HttpServletRequest request) {
    UnifiedResponse<Void> response = new UnifiedResponse<>(
            "CONFLICT",
            ex.getMessage(),
            DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            request.getRequestURI()
    );
    return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
}

@ExceptionHandler(BadRequestException.class)
public ResponseEntity<UnifiedResponse<Void>> handleBadRequest(BadRequestException ex, HttpServletRequest request) {
    UnifiedResponse<Void> response = new UnifiedResponse<>(
            "VALIDATION_ERROR",
            ex.getMessage(),
            DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            request.getRequestURI()
    );
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
}
```

- [ ] **Step 6: Run full core-app tests**
Run: `mvn -o -pl core-app test`
Expected: PASS — CoreAppIntegrationTest now has 8 original + 1 new VIEWER test = 9; the Task 2 contract test class (`AlertColumnContractVsCoreEntityTest`) runs in the same reactor pass. All green.

- [ ] **Step 7: Commit**
```bash
git add core-app
git commit -m "feat(core-app): enable method security and 403/404/409/400 exception handling with auth regression test"
```

---

### Task 4: Service Registry + API key lifecycle + audit (SUCCESS/FAILED)

**Files:**
- Create: `core-app/src/main/java/com/thinhbui303/observability/core/api/dto/ServiceCreateRequest.java`, `ServiceResponse.java`, `ServiceCreateResponse.java`, `UpdateServiceStatusRequest.java`
- Create: `core-app/src/main/java/com/thinhbui303/observability/core/service/ApiKeyGenerator.java`, `SlugBuilder.java`, `AuditRecord.java`, `AuditLogService.java`, `OperationContext.java`, `ServiceManagementService.java`
- Create: `core-app/src/main/java/com/thinhbui303/observability/core/api/ServiceController.java`
- Test: `core-app/src/test/java/com/thinhbui303/observability/core/TestSeeds.java`, `ServiceRegistryIntegrationTest.java`

**Interfaces:**
- Consumes: T2 entities/repos; T3 exceptions. `//Handlers` map 409/404.
- Produces: the Service Registry API (POST 200 + plaintext once, GET list, PATCH status), `AuditLogService`, `OperationContext`, `AuditRecord`, `ApiKeyGenerator`, `SlugBuilder`.

- [ ] **Step 1: Write the failing integration test**

`TestSeeds.java` (shared helpers for all later tasks):

```java
package com.thinhbui303.observability.core;

import com.thinhbui303.observability.core.domain.UserEntity;
import com.thinhbui303.observability.core.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

public final class TestSeeds {

    private TestSeeds() {
    }

    public static void seedUserWithRole(JdbcTemplate jdbcTemplate, UserRepository userRepository,
                                        PasswordEncoder encoder, String username, String role) {
        jdbcTemplate.execute("INSERT INTO roles (name) VALUES ('" + role + "') ON CONFLICT (name) DO NOTHING");
        if (userRepository.findByUsername(username).isEmpty()) {
            UserEntity u = new UserEntity();
            u.setUsername(username);
            u.setPasswordHash(encoder.encode("password123"));
            UserEntity saved = userRepository.save(u);
            jdbcTemplate.execute("INSERT INTO user_roles (user_id, role_id) VALUES (" + saved.getId() +
                    ", (SELECT id FROM roles WHERE name = '" + role + "'))");
        }
    }

    public static void deleteTestUsers(JdbcTemplate jdbcTemplate, String... usernames) {
        for (String u : usernames) {
            jdbcTemplate.update("DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE username = ?)", u);
            jdbcTemplate.update("DELETE FROM users WHERE username = ?", u);
        }
    }
}
```

`ServiceRegistryIntegrationTest.java`:

```java
package com.thinhbui303.observability.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinhbui303.observability.common.ApiKeyHashUtil;
import com.thinhbui303.observability.core.api.dto.LoginRequest;
import com.thinhbui303.observability.core.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ServiceRegistryIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    // slug("Test Service <suffix>") == "test-service-<suffix>" == TEST_SVC (stable per class)
    private static final String TEST_SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final String TEST_SVC_NAME = "Test Service " + TEST_SUFFIX;
    private static final String TEST_SVC = "test-service-" + TEST_SUFFIX;

    @BeforeEach
    void setUp() throws Exception {
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_admin_user", "ADMIN");
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_devops_user", "DEVOPS");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM audit_logs WHERE resource_target = 'services/" + TEST_SVC + "'");
        jdbcTemplate.update("DELETE FROM service_api_keys WHERE service_id = ?", TEST_SVC);
        jdbcTemplate.update("DELETE FROM services WHERE id = ?", TEST_SVC);
        TestSeeds.deleteTestUsers(jdbcTemplate, "test_admin_user", "test_devops_user");
    }

    private String jwtFor(String username) throws Exception {
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword("password123");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        int start = body.indexOf("\"accessToken\":\"") + 15;
        return body.substring(start, body.indexOf("\"", start));
    }

    @Test
    void testCreateService_ShouldReturnPlaintextKeyOnce() throws Exception {
        String body = """
                {
                    "name": "%s",
                    "teamOwner": "backend-team",
                    "environment": "production"
                }
                """.formatted(TEST_SVC_NAME);
        MvcResult result = mockMvc.perform(post("/api/v1/services")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(TEST_SVC))
                .andExpect(jsonPath("$.data.plaintextApiKey").exists())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        int start = response.indexOf("\"plaintextApiKey\":\"") + 18;
        String key = response.substring(start, response.indexOf("\"", start));
        plaintextKey = key;
        assertTrue(key.startsWith("sk_"));
        assertEquals(12, key.substring(0, 12).length());

        // DB stores ONLY the SHA-256 hash + prefix — never the plaintext
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT key_hash FROM service_api_keys WHERE service_id = ?", String.class, TEST_SVC);
        assertThat(storedHash).isEqualTo(ApiKeyHashUtil.hash(key));
    }

    @Test
    void testCreateService_ShouldWriteSuccessAuditRow() throws Exception {
        String body = """
                {
                    "name": "%s",
                    "teamOwner": "backend-team",
                    "environment": "production"
                }
                """.formatted(TEST_SVC_NAME);
        mockMvc.perform(post("/api/v1/services")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // BR-009 SUCCESS path: an audit row with the acting principal, action, target and IP.
        // MockMvc's MockHttpServletRequest defaults getRemoteAddr() to 127.0.0.1.
        Integer successRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'CREATE_SERVICE' AND result_status = 'SUCCESS'"
                        + " AND resource_target = 'services/" + TEST_SVC + "' AND username = 'test_admin_user'"
                        + " AND ip_address = '127.0.0.1'",
                Integer.class);
        assertEquals(1, successRows);
    }

    @Test
    void testGetServices_AsDevops_ShouldReturn200() throws Exception {
        mockMvc.perform(get("/api/v1/services")
                        .header("Authorization", "Bearer " + jwtFor("test_devops_user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    void testPatchServiceStatus_AsDevops_ShouldReturn403() throws Exception {
        // Pre-create a service via ADMIN so the target exists
        String body = """
                {
                    "name": "%s",
                    "teamOwner": "backend-team",
                    "environment": "production"
                }
                """.formatted(TEST_SVC_NAME);
        mockMvc.perform(post("/api/v1/services")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/services/" + TEST_SVC + "/status")
                        .header("Authorization", "Bearer " + jwtFor("test_devops_user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "status": "DISABLED" }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
}
```

Note: `testCreateService_ShouldReturnPlaintextKeyOnce` also proves **item 0** on the core-app side: the generated key's stored hash equals `ApiKeyHashUtil.hash(plaintext)` — the same util ingestion validates with.

- [ ] **Step 2: Run test to verify it fails**
Run: `mvn -o -pl core-app test -Dtest=ServiceRegistryIntegrationTest`
Expected: FAIL — endpoints 404.

- [ ] **Step 3: Implement DTOs (records)**

```java
package com.thinhbui303.observability.core.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateServiceRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 50) String teamOwner,
        @NotBlank @Size(max = 20) String environment
) {}
```
```java
package com.thinhbui303.observability.core.api.dto;

import com.thinhbui303.observability.common.ServiceStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateServiceStatusRequest(
        @NotNull ServiceStatus status
) {}
```
```java
package com.thinhbui303.observability.core.api.dto;

public record ServiceResponse(String id, String name, String teamOwner,
                              String environment, String status, String createdAt) {
}
```
```java
package com.thinhbui303.observability.core.api.dto;

public record ServiceCreateResponse(String id, String name, String environment,
                                    String plaintextApiKey, String keyPrefix) {
}
```

- [ ] **Step 4: Implement generators + audit plumbing**

`ApiKeyGenerator.java`:
```java
package com.thinhbui303.observability.core.service;

import java.security.SecureRandom;
import java.util.Base64;

public final class ApiKeyGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyGenerator() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "sk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
```
`SlugBuilder.java`:
```java
package com.thinhbui303.observability.core.service;

import java.util.Locale;

public final class SlugBuilder {

    private SlugBuilder() {
    }

    public static String slug(String name) {
        String slug = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return slug.isEmpty() ? "service" : slug;
    }
}
```
`AuditRecord.java`:
```java
package com.thinhbui303.observability.core.service;

import com.thinhbui303.observability.core.domain.AuditLogEntity;

public record AuditRecord(String username, String action, String resourceTarget,
                          String ipAddress, String resultStatus) {

    public static AuditRecord of(String username, String action, String resourceTarget,
                                 String ipAddress, String resultStatus) {
        return new AuditRecord(username, action, resourceTarget, ipAddress, resultStatus);
    }

    public AuditLogEntity toEntity() {
        AuditLogEntity e = new AuditLogEntity();
        e.setUsername(username);
        e.setAction(action);
        e.setResourceTarget(resourceTarget);
        e.setIpAddress(ipAddress);
        e.setResultStatus(resultStatus);
        return e;
    }
}
```
`AuditLogService.java` (the REQUIRED/REQUIRES_NEW pairing — mechanism of decision 1):
```java
package com.thinhbui303.observability.core.service;

import com.thinhbui303.observability.core.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {

    private final AuditLogRepository repository;

    public AuditLogService(AuditLogRepository repository) {
        this.repository = repository;
    }

    // REQUIRED: joins the caller's transaction — if the mutation rolls back, this row does too (atomic, BR-009).
    @Transactional
    public void recordSuccess(AuditRecord record) {
        repository.save(record.toEntity());
    }

    // REQUIRES_NEW: own transaction, commits independently — survives the outer transaction's rollback
    // when a ConflictException/BadRequestException is thrown AFTER this call. (decision 1, FAILED rows.)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(AuditRecord record) {
        repository.save(record.toEntity());
    }
}
```
`OperationContext.java`:
```java
package com.thinhbui303.observability.core.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class OperationContext {

    @Value("${app.audit.trust-forwarded:false}")
    private boolean trustForwarded;

    public String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return "anonymous";
        }
        return auth.getName();
    }

    public String resolveIp(HttpServletRequest request) {
        if (trustForwarded) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
```

- [ ] **Step 5: Implement `ServiceManagementService`**

```java
package com.thinhbui303.observability.core.service;

import com.thinhbui303.observability.common.ApiKeyHashUtil;
import com.thinhbui303.observability.core.api.dto.*;
import com.thinhbui303.observability.core.api.exception.NotFoundException;
import com.thinhbui303.observability.core.domain.ServiceApiKeyEntity;
import com.thinhbui303.observability.core.domain.ServiceEntity;
import com.thinhbui303.observability.core.repository.ServiceApiKeyRepository;
import com.thinhbui303.observability.core.repository.ServiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ServiceManagementService {

    private final ServiceRepository serviceRepository;
    private final ServiceApiKeyRepository serviceApiKeyRepository;
    private final AuditLogService auditLogService;

    public ServiceManagementService(ServiceRepository serviceRepository,
                                    ServiceApiKeyRepository serviceApiKeyRepository,
                                    AuditLogService auditLogService) {
        this.serviceRepository = serviceRepository;
        this.serviceApiKeyRepository = serviceApiKeyRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public ServiceCreateResponse create(CreateServiceRequest req, String username, String ip) {
        // Only services.id must be unique (LLD §2) — there is NO business rule on name uniqueness,
        // hence no app-level name check (would be unsound without a DB constraint) and no race window.
        String base = SlugBuilder.slug(req.name());
        String id = base;
        int n = 2;
        while (serviceRepository.existsById(id)) {
            id = base + "-" + n++;
        }

        ServiceEntity svc = new ServiceEntity();
        svc.setId(id);
        svc.setName(req.name());
        svc.setTeamOwner(req.teamOwner());
        svc.setEnvironment(req.environment());
        svc.setCreatedAt(Instant.now());
        serviceRepository.save(svc);

        String plain = ApiKeyGenerator.generate();
        ServiceApiKeyEntity key = new ServiceApiKeyEntity();
        key.setService(svc);
        key.setKeyPrefix(plain.substring(0, 12));
        key.setKeyHash(ApiKeyHashUtil.hash(plain));
        serviceApiKeyRepository.save(key);

        auditLogService.recordSuccess(AuditRecord.of(username, "CREATE_SERVICE", "services/" + id, ip, "SUCCESS"));
        return new ServiceCreateResponse(id, svc.getName(), svc.getEnvironment(), plain, key.getKeyPrefix());
    }

    @Transactional(readOnly = true)
    public List<ServiceResponse> list() {
        return serviceRepository.findAll().stream().map(ServiceManagementService::toResponse).toList();
    }

    @Transactional
    public ServiceResponse updateStatus(String id, UpdateServiceStatusRequest req, String username, String ip) {
        ServiceEntity svc = serviceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("SERVICE_NOT_FOUND", "Service not found: " + id));
        svc.setStatus(req.status());
        serviceRepository.save(svc);
        auditLogService.recordSuccess(AuditRecord.of(username, "UPDATE_SERVICE_STATUS", "services/" + id, ip, "SUCCESS"));
        return toResponse(svc);
    }

    private static ServiceResponse toResponse(ServiceEntity s) {
        return new ServiceResponse(s.getId(), s.getName(), s.getTeamOwner(), s.getEnvironment(),
                s.getStatus().name(), s.getCreatedAt().toString());
    }
}
```

- [ ] **Step 6: Implement `ServiceController`**

```java
package com.thinhbui303.observability.core.api;

import com.thinhbui303.observability.core.api.dto.*;
import com.thinhbui303.observability.core.service.OperationContext;
import com.thinhbui303.observability.core.service.ServiceManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/services")
public class ServiceController {

    private final ServiceManagementService service;
    private final OperationContext operationContext;

    public ServiceController(ServiceManagementService service, OperationContext operationContext) {
        this.service = service;
        this.operationContext = operationContext;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<UnifiedResponse<ServiceCreateResponse>> create(
            @Valid @RequestBody CreateServiceRequest req, HttpServletRequest request) {
        ServiceCreateResponse data = service.create(req, operationContext.currentUsername(), operationContext.resolveIp(request));
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS", data));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    public ResponseEntity<UnifiedResponse<List<ServiceResponse>>> list() {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS", service.list()));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<UnifiedResponse<ServiceResponse>> updateStatus(
            @PathVariable String id, @Valid @RequestBody UpdateServiceStatusRequest req, HttpServletRequest request) {
        ServiceResponse data = service.updateStatus(id, req, operationContext.currentUsername(), operationContext.resolveIp(request));
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS", data));
    }
}
```

- [ ] **Step 7: Run ServiceRegistry tests**
Run: `mvn -o -pl core-app test -Dtest=ServiceRegistryIntegrationTest`
Expected: PASS (4 tests). Verify the audit row literal `services/<TEST_SVC>` counts in the success-audit test match.

- [ ] **Step 8: Commit**
```bash
git add core-app
git commit -m "feat(core-app): service registry with once-only API key generation and audit success/failure rows"
```

---

### Task 5: Alert Rule Management + RBAC + validation + atomicity proof

**Files:**
- Create: `core-app/src/main/java/com/thinhbui303/observability/core/api/dto/ChannelRequest.java`, `CreateAlertRuleRequest.java`, `UpdateAlertRuleRequest.java`, `SetAlertRuleEnabledRequest.java`, `AlertRuleResponse.java`
- Create: `core-app/src/main/java/com/thinhbui303/observability/core/service/AlertRuleManagementService.java`
- Create: `core-app/src/main/java/com/thinhbui303/observability/core/api/AlertRuleController.java`
- Test: `core-app/src/test/java/com/thinhbui303/observability/core/AlertRuleManagementIntegrationTest.java`, `AuditAtomicityIntegrationTest.java`

**Interfaces:**
- Consumes: T2 entities (`AlertRuleEntity`, `AlertRuleChannelEntity`), `ServiceRepository`, `AlertRuleRepository`; T4 `AuditLogService`, `AuditRecord`, `OperationContext`; T3 exceptions.
- Produces: alert-rule CRUD API + the 403 and validation tests + atomicity proof (test #7).

- [ ] **Step 1: Write the failing tests**

`AlertRuleManagementIntegrationTest.java`:

```java
package com.thinhbui303.observability.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinhbui303.observability.core.api.dto.LoginRequest;
import com.thinhbui303.observability.core.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertRuleManagementIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String TEST_SVC = "test-alert-svc-" + UUID.randomUUID();
    private static final String TEST_RULE = "test-alert-rule-" + UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_admin_user", "ADMIN");
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_devops_user", "DEVOPS");
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_developer_user", "DEVELOPER");
        jdbcTemplate.update("INSERT INTO services (id, name, team_owner, environment) VALUES (?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                TEST_SVC, "Alert Test Service", "backend-team", "production");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM alert_rule_channels WHERE alert_rule_id IN (SELECT id FROM alert_rules WHERE rule_name LIKE 'test-alert-rule-%')");
        jdbcTemplate.update("DELETE FROM alert_rules WHERE rule_name LIKE 'test-alert-rule-%'");
        jdbcTemplate.update("DELETE FROM audit_logs WHERE resource_target LIKE 'alert-rules/%'");
        jdbcTemplate.update("DELETE FROM service_api_keys WHERE service_id = ?", TEST_SVC);
        jdbcTemplate.update("DELETE FROM services WHERE id = ?", TEST_SVC);
        TestSeeds.deleteTestUsers(jdbcTemplate, "test_admin_user", "test_devops_user", "test_developer_user");
    }

    private String jwtFor(String username) throws Exception { /* identical to ServiceRegistryIntegrationTest.jwtFor */ }

    @Test
    void testCreateAlertRule_AsDeveloper_ShouldReturn403() throws Exception {
        String body = """
                {
                    "ruleName": "es-spike",
                    "serviceId": "%s",
                    "environment": "production",
                    "conditionType": "ERROR_SPIKE",
                    "thresholdValue": 5,
                    "windowSeconds": 60,
                    "severity": "HIGH",
                    "notificationChannels": [ { "channelType": "SLACK", "target": "#ops-backend" } ]
                }
                """.formatted(TEST_SVC);
        mockMvc.perform(post("/api/v1/alert-rules")
                        .header("Authorization", "Bearer " + jwtFor("test_developer_user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void testCreateAlertRule_AsAdmin_ShouldReturnSuccess_WithChannelSaved() throws Exception {
        String body = """
                {
                    "ruleName": "%s",
                    "serviceId": "%s",
                    "environment": "production",
                    "conditionType": "ERROR_SPIKE",
                    "thresholdValue": 5,
                    "windowSeconds": 60,
                    "severity": "HIGH",
                    "notificationChannels": [ { "channelType": "TELEGRAM", "target": "@ops-bot", "enabled": true } ]
                }
                """.formatted(TEST_RULE, TEST_SVC);
        mockMvc.perform(post("/api/v1/alert-rules")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.notificationChannels", hasSize(1)));

        Integer channelCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM alert_rule_channels c JOIN alert_rules r ON r.id = c.alert_rule_id WHERE r.rule_name = ?",
                Integer.class, TEST_RULE);
        assertEquals(1, channelCount);
    }

    @Test
    void testPatternMatchRule_MissingConditionValue_ShouldReturnValidationError() throws Exception {
        String body = """
                {
                    "ruleName": "pm-missing-cond",
                    "serviceId": "%s",
                    "environment": "production",
                    "conditionType": "PATTERN_MATCH",
                    "severity": "HIGH"
                }
                """.formatted(TEST_SVC);
        mockMvc.perform(post("/api/v1/alert-rules")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Domain-layer rejections are audit FAILED (decision 1): must be persisted despite the 400
        Integer failed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'CREATE_ALERT_RULE' AND result_status = 'FAILED'",
                Integer.class);
        assertEquals(1, failed);
    }

    @Test
    void testPatternMatchRule_WithConditionValue_ShouldDefaultThresholdWindow() throws Exception {
        String body = """
                {
                    "ruleName": "pm-ok",
                    "serviceId": "%s",
                    "environment": "production",
                    "conditionType": "PATTERN_MATCH",
                    "conditionValue": "OutOfMemory",
                    "severity": "MEDIUM"
                }
                """.formatted(TEST_SVC);
        mockMvc.perform(post("/api/v1/alert-rules")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.thresholdValue").value(1))
                .andExpect(jsonPath("$.data.windowSeconds").value(60));
    }

    @Test
    void testUpdateRule_ShouldReplaceChannels() throws Exception {
        String created = """
                {
                    "ruleName": "%s",
                    "serviceId": "%s",
                    "environment": "production",
                    "conditionType": "ERROR_SPIKE",
                    "thresholdValue": 5,
                    "windowSeconds": 60,
                    "severity": "HIGH",
                    "notificationChannels": [ { "channelType": "SLACK", "target": "#a" } ]
                }
                """.formatted(TEST_RULE, TEST_SVC);
        MvcResult c = mockMvc.perform(post("/api/v1/alert-rules")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(created))
                .andExpect(status().isOk())
                .andReturn();
        String cBody = c.getResponse().getContentAsString();
        int idStart = cBody.indexOf("\"id\":") + 5;
        String ruleId = cBody.substring(idStart, cBody.indexOf(",", idStart));

        String updated = """
                {
                    "ruleName": "%s",
                    "serviceId": "%s",
                    "environment": "production",
                    "conditionType": "ERROR_SPIKE",
                    "thresholdValue": 9,
                    "windowSeconds": 120,
                    "severity": "HIGH",
                    "notificationChannels": [ { "channelType": "WEBHOOK", "target": "https://example.test/hook" },
                                               { "channelType": "SLACK", "target": "#b" } ]
                }
                """.formatted(TEST_RULE, TEST_SVC);
        mockMvc.perform(put("/api/v1/alert-rules/" + ruleId)
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updated))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.thresholdValue").value(9))
                .andExpect(jsonPath("$.data.notificationChannels", hasSize(2)));
    }
}
```

`AuditAtomicityIntegrationTest.java` (test #7 — the signature BR-009 mechanism):

```java
package com.thinhbui303.observability.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinhbui303.observability.core.api.dto.LoginRequest;
import com.thinhbui303.observability.core.domain.AlertRuleEntity;
import com.thinhbui303.observability.core.repository.AlertRuleRepository;
import com.thinhbui303.observability.core.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditAtomicityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @SpyBean
    private AlertRuleRepository alertRuleRepository;

    private static final String TEST_SVC = "test-atomic-svc-" + UUID.randomUUID();
    private static final String TEST_RULE = "test-atomic-rule-" + UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_admin_user", "ADMIN");
        jdbcTemplate.update("INSERT INTO services (id, name, team_owner, environment) VALUES (?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                TEST_SVC, "Atomic Test Service", "backend-team", "production");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM audit_logs WHERE resource_target LIKE 'alerts/%' OR resource_target LIKE 'services/%' OR resource_target LIKE 'alert-rules/%' OR resource_target LIKE 'users/%'");
        jdbcTemplate.update("DELETE FROM service_api_keys WHERE service_id = ?", TEST_SVC);
        jdbcTemplate.update("DELETE FROM services WHERE id = ?", TEST_SVC);
        TestSeeds.deleteTestUsers(jdbcTemplate, "test_admin_user");
    }

    private String jwtFor() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setUsername("test_admin_user");
        req.setPassword("password123");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        int start = body.indexOf("\"accessToken\":\"") + 15;
        return body.substring(start, body.indexOf("\"", start));
    }

    @Test
    void testAuditLog_WrittenAtomicallyWithMutation_ShouldRollbackTogetherOnFailure() throws Exception {
        doThrow(new DataAccessResourceFailureException("simulated infra failure")).when(alertRuleRepository).save(any(AlertRuleEntity.class));

        String body = """
                {
                    "ruleName": "%s",
                    "serviceId": "%s",
                    "environment": "production",
                    "conditionType": "ERROR_SPIKE",
                    "thresholdValue": 5,
                    "windowSeconds": 60,
                    "severity": "HIGH"
                }
                """.formatted(TEST_RULE, TEST_SVC);
        mockMvc.perform(post("/api/v1/alert-rules")
                        .header("Authorization", "Bearer " + jwtFor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));

        Integer ruleRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM alert_rules WHERE rule_name = ?", Integer.class, TEST_RULE);
        Integer auditRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'CREATE_ALERT_RULE' AND result_status = 'SUCCESS'",
                Integer.class);
        assertEquals(0, ruleRows, "main row must roll back");
        assertEquals(0, auditRows, "success audit row must roll back together with the mutation");
    }
}
```

- [ ] **Step 2: Run new tests to verify they fail**
Run: `mvn -o -pl core-app test -Dtest='AlertRuleManagementIntegrationTest,AuditAtomicityIntegrationTest'`
Expected: FAIL — endpoints 404.

- [ ] **Step 3: Implement the DTOs**

```java
package com.thinhbui303.observability.core.api.dto;

import com.thinhbui303.observability.common.ChannelType;
import jakarta.validation.constraints.NotNull;

public record ChannelRequest(
        @NotNull ChannelType channelType,
        String target,
        Boolean enabled
) {}
```
```java
package com.thinhbui303.observability.core.api.dto;

import com.thinhbui303.observability.common.ConditionType;
import com.thinhbui303.observability.common.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateAlertRuleRequest(
        @NotBlank @Size(max = 100) String ruleName,
        @NotBlank String serviceId,
        @NotBlank @Size(max = 20) String environment,
        @NotNull ConditionType conditionType,
        Integer thresholdValue,
        Integer windowSeconds,
        String conditionValue,
        @NotNull Severity severity,
        Boolean isEnabled,
        List<ChannelRequest> notificationChannels
) {}
```
```java
package com.thinhbui303.observability.core.api.dto;

public record UpdateAlertRuleRequest(
        @NotBlank @Size(max = 100) String ruleName,
        @NotBlank String serviceId,
        @NotBlank @Size(max = 20) String environment,
        @NotNull ConditionType conditionType,
        Integer thresholdValue,
        Integer windowSeconds,
        String conditionValue,
        @NotNull Severity severity,
        Boolean isEnabled,
        List<ChannelRequest> notificationChannels
) {}
```
```java
package com.thinhbui303.observability.core.api.dto;

import jakarta.validation.constraints.NotNull;

public record SetAlertRuleEnabledRequest(
        @NotNull Boolean enabled
) {}
```
```java
package com.thinhbui303.observability.core.api.dto;

import com.thinhbui303.observability.common.ChannelType;

import java.util.List;

public record AlertRuleChannelResponse(
        Long id, ChannelType channelType, String target, boolean enabled
) {}

public record AlertRuleResponse(
        Long id, String ruleName, String serviceId, String environment,
        String conditionType, Integer thresholdValue, Integer windowSeconds,
        String conditionValue, String severity, boolean isEnabled, String createdAt,
        List<AlertRuleChannelResponse> notificationChannels
) {}
```

- [ ] **Step 4: Implement `AlertRuleManagementService`**

```java
package com.thinhbui303.observability.core.service;

import com.thinhbui303.observability.common.ConditionType;
import com.thinhbui303.observability.core.api.dto.*;
import com.thinhbui303.observability.core.api.exception.BadRequestException;
import com.thinhbui303.observability.core.api.exception.NotFoundException;
import com.thinhbui303.observability.core.domain.AlertRuleChannelEntity;
import com.thinhbui303.observability.core.domain.AlertRuleEntity;
import com.thinhbui303.observability.core.domain.UserEntity;
import com.thinhbui303.observability.core.repository.AlertRuleRepository;
import com.thinhbui303.observability.core.repository.ServiceRepository;
import com.thinhbui303.observability.core.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AlertRuleManagementService {

    private static final String TARGET_PREFIX = "alert-rules/";

    private final AlertRuleRepository ruleRepository;
    private final ServiceRepository serviceRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public AlertRuleManagementService(AlertRuleRepository ruleRepository,
                                      ServiceRepository serviceRepository,
                                      UserRepository userRepository,
                                      AuditLogService auditLogService) {
        this.ruleRepository = ruleRepository;
        this.serviceRepository = serviceRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public AlertRuleResponse create(CreateAlertRuleRequest req, String username, String ip) {
        validateBusiness(req.conditionType(), req.thresholdValue(), req.windowSeconds(), req.conditionValue(),
                username, ip, "CREATE_ALERT_RULE");

        var rule = new AlertRuleEntity();
        apply(rule, req.ruleName(), req.serviceId(), req.environment(), req.conditionType(),
                req.thresholdValue(), req.windowSeconds(), req.conditionValue(), req.severity(),
                req.isEnabled(), req.notificationChannels());
        userRepository.findByUsername(username).ifPresent(rule::setCreatedBy);
        ruleRepository.save(rule);

        auditLogService.recordSuccess(AuditRecord.of(username, "CREATE_ALERT_RULE", TARGET_PREFIX + rule.getId(), ip, "SUCCESS"));
        return toResponse(rule);
    }

    @Transactional
    public AlertRuleResponse update(Long id, UpdateAlertRuleRequest req, String username, String ip) {
        validateBusiness(req.conditionType(), req.thresholdValue(), req.windowSeconds(), req.conditionValue(),
                username, ip, "UPDATE_ALERT_RULE");

        AlertRuleEntity rule = ruleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("ALERT_RULE_NOT_FOUND", "Alert rule not found: " + id));
        apply(rule, req.ruleName(), req.serviceId(), req.environment(), req.conditionType(),
                req.thresholdValue(), req.windowSeconds(), req.conditionValue(), req.severity(),
                req.isEnabled(), req.notificationChannels());
        ruleRepository.save(rule);

        auditLogService.recordSuccess(AuditRecord.of(username, "UPDATE_ALERT_RULE", TARGET_PREFIX + id, ip, "SUCCESS"));
        return toResponse(rule);
    }

    @Transactional
    public AlertRuleResponse setEnabled(Long id, SetAlertRuleEnabledRequest req, String username, String ip) {
        AlertRuleEntity rule = ruleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("ALERT_RULE_NOT_FOUND", "Alert rule not found: " + id));
        rule.setEnabled(req.enabled());
        ruleRepository.save(rule);
        auditLogService.recordSuccess(AuditRecord.of(username, "SET_ALERT_RULE_ENABLED", TARGET_PREFIX + id, ip, "SUCCESS"));
        return toResponse(rule);
    }

    @Transactional
    public void delete(Long id, String username, String ip) {
        AlertRuleEntity rule = ruleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("ALERT_RULE_NOT_FOUND", "Alert rule not found: " + id));
        ruleRepository.delete(rule);
        auditLogService.recordSuccess(AuditRecord.of(username, "DELETE_ALERT_RULE", TARGET_PREFIX + id, ip, "SUCCESS"));
    }

    @Transactional(readOnly = true)
    public List<AlertRuleResponse> list(String serviceId, String environment) {
        return ruleRepository.search(serviceId, environment).stream().map(this::toResponse).toList();
    }

    private void validateBusiness(ConditionType type, Integer threshold, Integer window, String conditionValue,
                                  String username, String ip, String action) {
        if (type == ConditionType.PATTERN_MATCH) {
            if (conditionValue == null || conditionValue.isBlank()) {
                auditLogService.recordFailure(AuditRecord.of(username, action, TARGET_PREFIX + "?", ip, "FAILED"));
                throw new BadRequestException("PATTERN_MATCH_REQUIRES_CONDITION_VALUE",
                        "PATTERN_MATCH rules require a non-blank conditionValue");
            }
        } else if (type == ConditionType.ERROR_SPIKE) {
            if (threshold == null || threshold <= 0 || window == null || window <= 0) {
                auditLogService.recordFailure(AuditRecord.of(username, action, TARGET_PREFIX + "?", ip, "FAILED"));
                throw new BadRequestException("ERROR_SPIKE_REQUIRES_POSITIVE_VALUES",
                        "ERROR_SPIKE rules require thresholdValue > 0 and windowSeconds > 0");
            }
        }
    }

    private void apply(AlertRuleEntity rule, String ruleName, String serviceId, String environment,
                       ConditionType type, Integer threshold, Integer window, String conditionValue,
                       Severity severity, Boolean isEnabled, List<ChannelRequest> channels) {
        rule.setRuleName(ruleName);
        rule.setService(serviceRepository.findById(serviceId)
                .orElseThrow(() -> new NotFoundException("SERVICE_NOT_FOUND", "Service not found: " + serviceId)));
        rule.setEnvironment(environment);
        rule.setConditionType(type);
        rule.setConditionValue(conditionValue);
        rule.setSeverity(severity);
        rule.setEnabled(isEnabled == null || isEnabled);
        if (type == ConditionType.PATTERN_MATCH) {
            rule.setThresholdValue(threshold == null ? 1 : threshold);
            rule.setWindowSeconds(window == null ? 60 : window);
        } else {
            rule.setThresholdValue(threshold);
            rule.setWindowSeconds(window);
        }
        rule.getChannels().clear();
        if (channels != null) {
            for (ChannelRequest c : channels) {
                AlertRuleChannelEntity ch = new AlertRuleChannelEntity();
                ch.setChannelType(c.channelType());
                ch.setTarget(c.target());
                ch.setEnabled(c.enabled() == null || c.enabled());
                rule.addChannel(ch);
            }
        }
    }

    private AlertRuleResponse toResponse(AlertRuleEntity r) {
        return new AlertRuleResponse(
                r.getId(), r.getRuleName(), r.getService().getId(), r.getEnvironment(),
                r.getConditionType().name(), r.getThresholdValue(), r.getWindowSeconds(),
                r.getConditionValue(), r.getSeverity().name(), r.isEnabled(), r.getCreatedAt().toString(),
                r.getChannels().stream()
                        .map(c -> new AlertRuleChannelResponse(c.getId(), c.getChannelType(), c.getTarget(), c.isEnabled()))
                        .toList());
    }
}
```

Add the missing `Severity` import to the file (`com.thinhbui303.observability.common.Severity`).

- [ ] **Step 5: Implement `AlertRuleController`**

```java
package com.thinhbui303.observability.core.api;

import com.thinhbui303.observability.core.api.dto.*;
import com.thinhbui303.observability.core.service.AlertRuleManagementService;
import com.thinhbui303.observability.core.service.OperationContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/alert-rules")
public class AlertRuleController {

    private final AlertRuleManagementService service;
    private final OperationContext operationContext;

    public AlertRuleController(AlertRuleManagementService service, OperationContext operationContext) {
        this.service = service;
        this.operationContext = operationContext;
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    public ResponseEntity<UnifiedResponse<AlertRuleResponse>> create(
            @Valid @RequestBody CreateAlertRuleRequest req, HttpServletRequest request) {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS",
                service.create(req, operationContext.currentUsername(), operationContext.resolveIp(request))));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    public ResponseEntity<UnifiedResponse<AlertRuleResponse>> update(
            @PathVariable Long id, @Valid @RequestBody UpdateAlertRuleRequest req, HttpServletRequest request) {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS",
                service.update(id, req, operationContext.currentUsername(), operationContext.resolveIp(request))));
    }

    @PatchMapping("/{id}/enabled")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    public ResponseEntity<UnifiedResponse<AlertRuleResponse>> setEnabled(
            @PathVariable Long id, @Valid @RequestBody SetAlertRuleEnabledRequest req, HttpServletRequest request) {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS",
                service.setEnabled(id, req, operationContext.currentUsername(), operationContext.resolveIp(request))));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    public ResponseEntity<UnifiedResponse<Void>> delete(@PathVariable Long id, HttpServletRequest request) {
        service.delete(id, operationContext.currentUsername(), operationContext.resolveIp(request));
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS", null));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    public ResponseEntity<UnifiedResponse<List<AlertRuleResponse>>> list(
            @RequestParam(required = false) String service, @RequestParam(required = false) String environment) {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS", service.list(service, environment)));
    }
}
```

- [ ] **Step 6: Run the full core-app suite**
Run: `mvn -o -pl core-app test`
Expected: PASS — all prior + 6 new tests (4 management + atomicity + result). Verify atomicity counts.

- [ ] **Step 7: Commit**
```bash
git add core-app
git commit -m "feat(core-app): alert rule CRUD with RBAC, pattern-match validation and audit atomicity proof"
```

---

### Task 6: Alert Lifecycle (acknowledge / resolve) + state machine audits

**Files:**
- Create: `core-app/src/main/java/com/thinhbui303/observability/core/api/dto/AlertResponse.java`
- Create: `core-app/src/main/java/com/thinhbui303/observability/core/service/AlertLifecycleService.java`
- Create: `core-app/src/main/java/com/thinhbui303/observability/core/api/AlertController.java`
- Test: `core-app/src/test/java/com/thinhbui303/observability/core/AlertLifecycleIntegrationTest.java`

**Interfaces:**
- Consumes: T2 `AlertRepository`/`AlertEntity`; T4 audit infra; T3 exceptions.
- Produces: GET `/api/v1/alerts` (any authenticated), PATCH acknowledge/resolve with 409s and correct timestamps (tests #5, #6, #8).

- [ ] **Step 1: Write the failing test**

```java
package com.thinhbui303.observability.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinhbui303.observability.core.api.dto.LoginRequest;
import com.thinhbui303.observability.core.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertLifecycleIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String TEST_SVC = "test-alert-lifecycle-svc-" + UUID.randomUUID();
    private static final String TEST_ALERT = "test-alert-" + UUID.randomUUID();
    private static final String TEST_ALERT2 = "test-alert-" + UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_admin_user", "ADMIN");
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_devops_user", "DEVOPS");
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_viewer_user", "VIEWER");
        jdbcTemplate.update("INSERT INTO services (id, name, team_owner, environment) VALUES (?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                TEST_SVC, "Lifecycle Test Service", "backend-team", "production");
        jdbcTemplate.update("INSERT INTO alerts (id, service_id, environment, window_start, severity, status, triggered_at, occurrence_count) VALUES (?, ?, 'production', ?, 'HIGH', 'TRIGGERED', ?, 1)",
                TEST_ALERT, TEST_SVC, Instant.now().minusSeconds(300), Instant.now());
        jdbcTemplate.update("INSERT INTO alerts (id, service_id, environment, window_start, severity, status, triggered_at, occurrence_count) VALUES (?, ?, 'production', ?, 'HIGH', 'OPEN', ?, 1)",
                TEST_ALERT2, TEST_SVC, Instant.now().minusSeconds(300), Instant.now());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM audit_logs WHERE resource_target IN ('alerts/" + TEST_ALERT + "', 'alerts/" + TEST_ALERT2 + "')");
        jdbcTemplate.update("DELETE FROM alerts WHERE id LIKE 'test-alert-%'");
        jdbcTemplate.update("DELETE FROM service_api_keys WHERE service_id = ?", TEST_SVC);
        jdbcTemplate.update("DELETE FROM services WHERE id = ?", TEST_SVC);
        TestSeeds.deleteTestUsers(jdbcTemplate, "test_admin_user", "test_devops_user", "test_viewer_user");
    }

    private String jwtFor(String username) throws Exception { /* identical helper */ }

    @Test
    void testAlertLifecycle_ResolveBeforeAcknowledge_ShouldReturn409() throws Exception {
        mockMvc.perform(patch("/api/v1/alerts/" + TEST_ALERT + "/resolve")
                        .header("Authorization", "Bearer " + jwtFor("test_devops_user")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void testAlertLifecycle_ValidTransition_ShouldSetTimestampsCorrectly() throws Exception {
        mockMvc.perform(patch("/api/v1/alerts/" + TEST_ALERT + "/acknowledge")
                        .header("Authorization", "Bearer " + jwtFor("test_viewer_user")))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/alerts/" + TEST_ALERT + "/acknowledge")
                        .header("Authorization", "Bearer " + jwtFor("test_devops_user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.data.acknowledgedAt", notNullValue()))
                .andExpect(jsonPath("$.data.resolvedAt", nullValue()));

        mockMvc.perform(patch("/api/v1/alerts/" + TEST_ALERT + "/resolve")
                        .header("Authorization", "Bearer " + jwtFor("test_devops_user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.resolvedAt", notNullValue()));
    }

    @Test
    void testAlertLifecycle_OpenState_IsAcknowledgedLikeTriggered() throws Exception {
        mockMvc.perform(patch("/api/v1/alerts/" + TEST_ALERT2 + "/acknowledge")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACKNOWLEDGED"));
    }

    @Test
    void testAlertLifecycle_ResolvedAlert_RejectsFurtherTransitions() throws Exception {
        mockMvc.perform(patch("/api/v1/alerts/" + TEST_ALERT + "/acknowledge")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user")))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/alerts/" + TEST_ALERT + "/resolve")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user")))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/alerts/" + TEST_ALERT + "/acknowledge")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void testAuditLog_FailedStateTransition_ShouldPersistFailedRow() throws Exception {
        mockMvc.perform(patch("/api/v1/alerts/" + TEST_ALERT + "/resolve")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user")))
                .andExpect(status().isConflict());

        Integer failed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE resource_target = 'alerts/" + TEST_ALERT +
                        "' AND result_status = 'FAILED'", Integer.class);
        Integer success = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE resource_target = 'alerts/" + TEST_ALERT +
                        "' AND result_status = 'SUCCESS'", Integer.class);
        assertEquals(1, failed, "FAILED audit row must be persisted despite the 409 (decision 1)");
        assertEquals(0, success, "no SUCCESS audit row may exist for a rejected transition");
    }

    @Test
    void testGetAlerts_AsViewer_ShouldReturnAll() throws Exception {
        mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer " + jwtFor("test_viewer_user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }
}
```

Note the deliberate assertion inside `testAlertLifecycle_ValidTransition_ShouldSetTimestampsCorrectly`: a VIEWER is forbidden from acknowledging (403) — verifying the role matrix precisely.

- [ ] **Step 2: Run test to verify it fails**
Run: `mvn -o -pl core-app test -Dtest=AlertLifecycleIntegrationTest`
Expected: FAIL — endpoints 404.

- [ ] **Step 3: Implement `AlertResponse`, `AlertLifecycleService`, `AlertController`**

```java
package com.thinhbui303.observability.core.api.dto;

public record AlertResponse(
        String id, Long ruleId, String serviceId, String environment,
        String windowStart, String severity, String status,
        String triggeredAt, String acknowledgedAt, String resolvedAt,
        int occurrenceCount
) {}
```
```java
package com.thinhbui303.observability.core.service;

import com.thinhbui303.observability.common.AlertStatus;
import com.thinhbui303.observability.core.api.dto.AlertResponse;
import com.thinhbui303.observability.core.api.exception.ConflictException;
import com.thinhbui303.observability.core.api.exception.NotFoundException;
import com.thinhbui303.observability.core.domain.AlertEntity;
import com.thinhbui303.observability.core.repository.AlertRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class AlertLifecycleService {

    private final AlertRepository alertRepository;
    private final AuditLogService auditLogService;

    public AlertLifecycleService(AlertRepository alertRepository, AuditLogService auditLogService) {
        this.alertRepository = alertRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<AlertResponse> list(String serviceId, String environment, String status) {
        return alertRepository.search(serviceId, environment, status).stream().map(this::toResponse).toList();
    }

    @Transactional
    public AlertResponse acknowledge(String id, String username, String ip) {
        AlertEntity alert = findOrThrow(id);
        String st = alert.getStatus();
        // Decision 2: OPEN ≡ alias of TRIGGERED — acknowledge valid from both
        if (!st.equals(AlertStatus.TRIGGERED.name()) && !st.equals(AlertStatus.OPEN.name())) {
            auditLogService.recordFailure(AuditRecord.of(username, "ACKNOWLEDGE_ALERT", "alerts/" + id, ip, "FAILED"));
            throw new ConflictException("ALERT_NOT_ACKNOWLEDGEABLE",
                    "An alert can only be acknowledged while TRIGGERED/OPEN, was " + st);
        }
        alert.setStatus(AlertStatus.ACKNOWLEDGED.name());
        alert.setAcknowledgedAt(Instant.now());
        alertRepository.save(alert);
        auditLogService.recordSuccess(AuditRecord.of(username, "ACKNOWLEDGE_ALERT", "alerts/" + id, ip, "SUCCESS"));
        return toResponse(alert);
    }

    @Transactional
    public AlertResponse resolve(String id, String username, String ip) {
        AlertEntity alert = findOrThrow(id);
        if (!alert.getStatus().equals(AlertStatus.ACKNOWLEDGED.name())) {
            auditLogService.recordFailure(AuditRecord.of(username, "RESOLVE_ALERT", "alerts/" + id, ip, "FAILED"));
            throw new ConflictException("ALERT_NOT_RESOLVABLE",
                    "An alert can only be resolved after it is ACKNOWLEDGED, was " + alert.getStatus());
        }
        alert.setStatus(AlertStatus.RESOLVED.name());
        alert.setResolvedAt(Instant.now());
        alertRepository.save(alert);
        auditLogService.recordSuccess(AuditRecord.of(username, "RESOLVE_ALERT", "alerts/" + id, ip, "SUCCESS"));
        return toResponse(alert);
    }

    private AlertEntity findOrThrow(String id) {
        return alertRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("ALERT_NOT_FOUND", "Alert not found: " + id));
    }

    private AlertResponse toResponse(AlertEntity a) {
        return new AlertResponse(a.getId(), a.getRuleId(), a.getServiceId(), a.getEnvironment(),
                a.getWindowStart() == null ? null : a.getWindowStart().toString(),
                a.getSeverity(), a.getStatus(),
                a.getTriggeredAt() == null ? null : a.getTriggeredAt().toString(),
                a.getAcknowledgedAt() == null ? null : a.getAcknowledgedAt().toString(),
                a.getResolvedAt() == null ? null : a.getResolvedAt().toString(),
                a.getOccurrenceCount());
    }
}
```
```java
package com.thinhbui303.observability.core.api;

import com.thinhbui303.observability.core.api.dto.AlertResponse;
import com.thinhbui303.observability.core.service.AlertLifecycleService;
import com.thinhbui303.observability.core.service.OperationContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertLifecycleService service;
    private final OperationContext operationContext;

    public AlertController(AlertLifecycleService service, OperationContext operationContext) {
        this.service = service;
        this.operationContext = operationContext;
    }

    @GetMapping
    public ResponseEntity<UnifiedResponse<List<AlertResponse>>> list(
            @RequestParam(required = false) String serviceId,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS", service.list(serviceId, environment, status)));
    }

    @PatchMapping("/{id}/acknowledge")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS', 'ROLE_DEVELOPER')")
    public ResponseEntity<UnifiedResponse<AlertResponse>> acknowledge(
            @PathVariable String id, HttpServletRequest request) {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS",
                service.acknowledge(id, operationContext.currentUsername(), operationContext.resolveIp(request))));
    }

    @PatchMapping("/{id}/resolve")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    public ResponseEntity<UnifiedResponse<AlertResponse>> resolve(
            @PathVariable String id, HttpServletRequest request) {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS",
                service.resolve(id, operationContext.currentUsername(), operationContext.resolveIp(request))));
    }
}
```

- [ ] **Step 4: Run the full core-app suite**
Run: `mvn -o -pl core-app test`
Expected: PASS — all prior + 6 lifecycle tests.

- [ ] **Step 5: Commit**
```bash
git add core-app
git commit -m "feat(core-app): alert lifecycle acknowledge/resolve with OPEN alias and failed-transition audits"
```

---

### Task 7: Audit Log read API + DEVOPS operational scope

**Files:**
- Create: `core-app/src/main/java/com/thinhbui303/observability/core/api/dto/AuditLogResponse.java`
- Create: `core-app/src/main/java/com/thinhbui303/observability/core/service/AuditLogQueryService.java`
- Create: `core-app/src/main/java/com/thinhbui303/observability/core/api/AuditLogController.java`
- Test: `core-app/src/test/java/com/thinhbui303/observability/core/AuditLogReadIntegrationTest.java`

**Interfaces:**
- Consumes: T2 `AuditLogRepository`; T3 exceptions; audit rows produced by T4-T6.
- Produces: GET `/api/v1/audit-logs` with ADMIN-full / DEVOPS-operational-scope filtering.

- [ ] **Step 1: Write the failing test**

```java
package com.thinhbui303.observability.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinhbui303.observability.core.api.dto.LoginRequest;
import com.thinhbui303.observability.core.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditLogReadIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String TEST_PREFIX = "test-audit-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() throws Exception {
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_admin_user", "ADMIN");
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_devops_user", "DEVOPS");

        // Seed a known audit dataset: two rows INSIDE the DEVOPS operational scope, two OUTSIDE it.
        jdbcTemplate.update("INSERT INTO audit_logs (username, action, resource_target, ip_address, result_status, created_at) VALUES ('ops-user', 'CREATE_SERVICE', 'services/" + TEST_PREFIX + "', '127.0.0.1', 'SUCCESS', now())");
        jdbcTemplate.update("INSERT INTO audit_logs (username, action, resource_target, ip_address, result_status, created_at) VALUES ('ops-user', 'CREATE_ALERT_RULE', 'alert-rules/" + TEST_PREFIX + "', '127.0.0.1', 'SUCCESS', now())");
        jdbcTemplate.update("INSERT INTO audit_logs (username, action, resource_target, ip_address, result_status, created_at) VALUES ('ops-user', 'ACKNOWLEDGE_ALERT', 'alerts/" + TEST_PREFIX + "', '127.0.0.1', 'SUCCESS', now())");
        jdbcTemplate.update("INSERT INTO audit_logs (username, action, resource_target, ip_address, result_status, created_at) VALUES ('ops-user', 'CREATE_USER', 'users/" + TEST_PREFIX + "', '127.0.0.1', 'SUCCESS', now())");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM audit_logs WHERE resource_target LIKE '" + TEST_PREFIX + "' OR resource_target LIKE 'services/" + TEST_PREFIX + "' OR resource_target LIKE 'alert-rules/" + TEST_PREFIX + "' OR resource_target LIKE 'alerts/" + TEST_PREFIX + "' OR resource_target LIKE 'users/" + TEST_PREFIX + "'");
        TestSeeds.deleteTestUsers(jdbcTemplate, "test_admin_user", "test_devops_user", "test_developer_user");
    }

    private String jwtFor(String username) throws Exception { /* identical helper */ }

    @Test
    void testAuditLogs_AsDevops_ShouldOnlySeeOperationalScope() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs")
                        .header("Authorization", "Bearer " + jwtFor("test_devops_user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].resourceTarget", everyItem(
                        anyOf(startsWith("services/"), startsWith("alert-rules/")))));
    }

    @Test
    void testAuditLogs_AsAdmin_ShouldSeeAll() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(4)));
    }

    @Test
    void testAuditLogs_AsDeveloper_ShouldReturn403() throws Exception {
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_developer_user", "DEVELOPER");
        mockMvc.perform(get("/api/v1/audit-logs")
                        .header("Authorization", "Bearer " + jwtFor("test_developer_user")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void testAuditLogs_FilterByResourceTarget_ShouldApply() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs")
                        .param("resourceTarget", TEST_PREFIX)
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(4)));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**
Run: `mvn -o -pl core-app test -Dtest=AuditLogReadIntegrationTest`
Expected: FAIL — endpoint 404.

- [ ] **Step 3: Implement response, query service, controller**

```java
package com.thinhbui303.observability.core.api.dto;

public record AuditLogResponse(
        Long id, String username, String action, String resourceTarget,
        String ipAddress, String resultStatus, String createdAt
) {}
```
```java
package com.thinhbui303.observability.core.service;

import com.thinhbui303.observability.core.api.dto.AuditLogResponse;
import com.thinhbui303.observability.core.domain.AuditLogEntity;
import com.thinhbui303.observability.core.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AuditLogQueryService {

    private static final List<String> DEVOPS_SCOPES = List.of("services/", "alert-rules/");

    private final AuditLogRepository repository;

    public AuditLogQueryService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> list(String action, String username, String resourceTarget, boolean devopsScoped) {
        List<AuditLogEntity> rows = repository.search(action, username, resourceTarget);
        // Decision 1: DEVOPS sees ONLY the operational scope (services/*, alert-rules/*) —
        // never permission/user-change or other activity.
        if (devopsScoped) {
            return rows.stream()
                    .filter(r -> DEVOPS_SCOPES.stream().anyMatch(prefix -> r.getResourceTarget().startsWith(prefix)))
                    .map(this::toResponse)
                    .toList();
        }
        return rows.stream().map(this::toResponse).toList();
    }

    private AuditLogResponse toResponse(AuditLogEntity a) {
        return new AuditLogResponse(a.getId(), a.getUsername(), a.getAction(), a.getResourceTarget(),
                a.getIpAddress(), a.getResultStatus(), a.getCreatedAt().toString());
    }
}
```
```java
package com.thinhbui303.observability.core.api;

import com.thinhbui303.observability.core.api.dto.AuditLogResponse;
import com.thinhbui303.observability.core.repository.UserRepository;
import com.thinhbui303.observability.core.service.AuditLogQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {

    private final AuditLogQueryService queryService;
    private final UserRepository userRepository;

    public AuditLogController(AuditLogQueryService queryService, UserRepository userRepository) {
        this.queryService = queryService;
        this.userRepository = userRepository;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    public ResponseEntity<UnifiedResponse<List<AuditLogResponse>>> list(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String resourceTarget,
            Authentication authentication) {
        boolean devopsScoped = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_DEVOPS"))
                && authentication.getAuthorities().stream()
                           .noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS",
                queryService.list(action, username, resourceTarget, devopsScoped)));
    }
}
```

- [ ] **Step 4: Run the full core-app suite**
Run: `mvn -o -pl core-app test`
Expected: PASS — all prior + 4 audit-read tests.

- [ ] **Step 5: Commit**
```bash
git add core-app
git commit -m "feat(core-app): audit log read API with DEVOPS operational-scope filtering"
```

---

### Task 8: Docs, full-reactor verification, completion report

**Files:**
- Modify: `README.md` — API section for the four new surfaces + auth matrix, the three locked decisions, audit semantics (SUCCESS same-tx / FAILED REQUIRES_NEW / FAILED scope), the `app.audit.trust-forwarded` flag, the ≤30 s analytics rule-cache note, slug id + once-only API key note, `AlertColumnContract` dual-module rationale.

**Interfaces:**
- Consumes: every task above; final state = all reactor tests green.

- [ ] **Step 1: Verify full reactor green (regression check — required by item 7)**

Run: `mvn -o clean test`
Expected: BUILD SUCCESS — every module. Prior baseline was 42 tests (platform-common 1, ingestion 5→7, indexer 12, analytics 6, core-app 8→18, alert-consumer 3→4, notification-dispatcher 7); record the NEW exact numbers in the completion report. Re-run `mvn -o clean test` twice more; all three runs green → evidence the async/DB interactions are stable.

- [ ] **Step 2: Write the README API + decisions section**

Add a concise section documenting:
1. RBAC table (SRS 13.2 verbatim mapping to endpoints).
2. Audit semantics (the REQUIRED/REQUIRES_NEW pairing, FAILED-scope rule, `X-Forwarded-For` trust flag).
3. Locked decisions (DEVOPS scope; OPEN≡TRIGGERED state machine `TRIGGERED/OPEN→ACKNOWLEDGED→RESOLVED`; slug service id).
4. API key lifecycle: generated once (`sk_` + 32 B), plaintext returned only on creation, `key_prefix` (12) + SHA-256 `key_hash` stored; ingestion already enforces `status='ACTIVE'` (`ApiKeyValidator.java:30`).
5. Analytics rule-cache note: rule changes reflected in `analytics-engine` within ≤30 s (`analytics.rule.cache.refresh-rate:30000`), by design.
6. `AlertColumnContract` rationale: two modules map `alerts`; the shared fixture + per-module contract tests catch drift.

- [ ] **Step 3: Commit**
```bash
git add README.md
git commit -m "docs: record Slice 5 API, RBAC matrix, audit semantics and locked decisions"
```

- [ ] **Step 4: Write the Vietnamese completion report**

Create `.superpowers/sdd/2026-09-06-slice5-service-registry-alert-management-audit-log/completion-report.md` mirroring the Slice 4 pattern with:
- the final reactor test counts per module + the 3× green runs;
- item-0 evidence: `ApiKeyHashUtil` golden vectors; `ApiKeyValidator` delegates to it; the cross-module named test; the pre-existing `s.status='ACTIVE'` check at `ApiKeyValidator.java:30` (NOT patched — evidence link);
- decision-1 proof: the `recordSuccess`/`recordFailure` code (REQUIRED vs REQUIRES_NEW) + test #8 (`..._ShouldPersistFailedRow`) + test #7 (atomic rollback) results;
- decision-2 proof: `AlertColumnContract` + both contract tests green (field-by-field drift check);
- decision-3 proof: slug-derived `id` (`testCreateService_ShouldReturnPlaintextKeyOnce` asserts `$.data.id` == `TEST_SVC`; `id`-collision suffix loop in `create()`; no name-uniqueness rule — per user decision, matching LLD §2 where only `id` is unique);
- the three locked decisions recap; the 9 named tests table; the `@EnableMethodSecurity` + 403/404/409/400 handlers with the regression run of the 8 original core-app tests.

- [ ] **Step 5: Final review gate**

Run: `git status` clean check (only `.superpowers/` untracked), `git log --oneline -12`. Present the completion report to the user for the pre-Slice-6 sign-off (per item 7: do NOT start Slice 6 until item 6 passes and item 7 is confirmed with evidence).

---

## Self-Review

- **Spec coverage:** Item 0 → T1/T4 (hash util, cross-module named test, plaintext-once test). Item 1 → T3 (method security + 403) + T4-T7 (`@PreAuthorize` on every endpoint) + T7 DEVOPS scope. Item 2 → T4 (register/list/status + once-only key + slug). Item 3 → T5 (CRUD + channels cascade + validation + ≤30 s doc in T8). Item 4 → T6 (GET any-auth, ack 3 roles, resolve 2 roles, 409 on invalid transition, timestamps). Item 5 → T4/T5/T6 (audit on every mutation, atomicity, `X-Forwarded-For` flag), T7 (read API). Item 6 → tests #1-T1, #2-T4, #3-T1, #4-T5, #5-T6, #6-T6, #7-T5(atomicity), #8-T6, #9-T5. Item 7 → T8. The 3 locked decisions → decisions 1/2/3 (audit scope, OPEN alias, slug id) all implemented and documented. No task is missing.
- **Placeholder scan:** All steps carry concrete code; no "implement later"/"TBD". Entity getters/setters are abbreviated by convention (codebase style, note embedded), never logic.
- **Type consistency:** Cross-task names match the Interfaces block verbatim (`ApiKeyHashUtil.hash`, `AlertColumnContract.ALERTS`, `AuditRecord.of`, `recordSuccess`/`recordFailure`, `OperationContext.currentUsername()`/`resolveIp`, `ConflictException`/`BadRequestException`/`NotFoundException`, `AlertRuleRepository.search`, `AlertRepository.search`, `AuditLogRepository.search`, DTO `data.*` JSON paths). `CreateServiceRequest`/`ServiceResponse`/`AlertRuleResponse`/`AlertResponse`/`AuditLogResponse` names consistent from creation through all controllers/services/tests.