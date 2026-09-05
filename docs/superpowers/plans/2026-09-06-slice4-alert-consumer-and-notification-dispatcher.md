# Slice 4: Alert Consumer & Notification Dispatcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement two independent Kafka consumers on `system-alerts`: `alert-consumer` (idempotent persistence into PostgreSQL `alerts` via a local transaction) and `notification-dispatcher` (Redis cooldown + Slack/Telegram/WEBHOOK webhook dispatch, reading channel config from PostgreSQL) — without cross-module coupling.

**Architecture:** Two standalone Spring Boot modules, each its own consumer group (`alert-persistence-group`, `notification-dispatcher-group`). `alert-consumer` = @Transactional persist with duplicate detection (PK `id` + business key) and manual ACK only after commit. `notification-dispatcher` = per-alert channel resolution from `alert_rule_channels`, Redis cooldown/counter via atomic Lua, controlled degraded mode on Redis down. Both modules already exist as skeletons in the Maven reactor.

**Tech Stack:** Spring Boot 3.3.4, Spring Kafka (@KafkaListener, Manual Ack), Spring Data JPA (local PostgreSQL transaction), Jackson (`CanonicalAlertEvent` JSON, from `platform-common`), Redis (Spring Data Redis + Lua script), `RestTemplate` webhook client (mocked in tests with WireMock), JUnit 5 + Awaitility + WireMock (infra via docker-compose).

**Spec:** `docs/LLD_v1.1_Observability_Platform.md` §2.1 (AlertEntity), §6.1, §6.2 (Cooldown Lock), §10.2 (Transaction Boundaries), §11.3 (business-key dedup); `docs/SDD_v1.1_Observability_Platform.md` §4.2, §4.3, §6.1, §6.2, §7.3, §7.4; this prompt's field mapping and items 0-5.

## Key Pre-Code Findings (already verified — act on these, do NOT re-investigate)

1. **No schema migration is needed for `alerts`.** `platform-db/src/main/resources/db/migration/V4__create_alerts.sql` ALREADY contains `environment`, `window_start`, `occurrence_count`, and `CONSTRAINT uq_alerts_business_key UNIQUE (rule_id, service_id, environment, window_start)` (line 16). Verified live in PostgreSQL: 11 columns exist; constraints `alerts_pkey`, `uq_alerts_business_key`, `alerts_rule_id_fkey`, `alerts_service_id_fkey` present. Do NOT create a new migration.
2. **`serviceName` in the pipeline is already the `services.id`.** Chain verified: `ApiKeyValidator.java:25-34` (SELECT `ak.service_id`) -> `identity.serviceId()` -> `CanonicalEventBuilder.java:28` `serviceName(identity.serviceId())` -> `CanonicalLogEvent.serviceName` -> `String serviceId = value.serviceName()` (`ErrorWindowStreamTopology.java:53`, `PatternMatchStreamTopology.java:40`) -> `CanonicalAlertEvent.serviceId`. So FK `alerts.service_id REFERENCES services(id)` never fails from a naming mismatch. No source fix at `analytics-engine` needed.
3. **Business-key dedup decision is ALREADY decided AND enforced at the DB** (spec item 2): `uq_alerts_business_key`. A single window/rule/service IS a single business alert; repeated emits are duplicates of that alert. `alert-consumer` handles BOTH unique violations: (a) PK `id` violation -> existing alert, idempotent success, no write; (b) business-key violation -> existing alert, increment `occurrence_count`. Record this decision in code comments and README.
4. **Migrations present (no re-work, no re-numbering):** `V1__create_users_roles.sql`, `V2__create_services.sql`, `V3__create_alert_rules.sql`, `V4__create_alerts.sql`, `V5__create_audit_logs.sql`, `V6__seed_users_roles.sql`, `V7__alter_alert_rules_add_condition_value.sql`. None missing, no duplicate numbers.
5. **`AlertEntity` does not exist anywhere yet** — this plan creates it in `alert-consumer` (maps `alerts` for persistence). `notification-dispatcher` does NOT persist alerts.
6. **`notification-dispatcher` currently excludes `DataSourceAutoConfiguration`** (`NotificationDispatcherApplication.java:7`) — must be changed so this module can read `alert_rule_channels` from PostgreSQL.
7. **`alert-consumer` reads `system-alerts` produced as JSON by `analytics-engine`** using `JsonSerializer<CanonicalAlertEvent>`/`JsonDeserializer` (verified in `KafkaStreamsConfig.java:51-65`). The new modules consume the same JSON via a plain `StringDeserializer` + `ObjectMapper.readValue(..., CanonicalAlertEvent.class)`, matching the pattern in `AnalyticsEngineIntegrationTest.java:128`.

## Global Constraints

- No Testcontainers anywhere. Infra via `docker-compose up -d`; connect to `localhost:9092` (Kafka), `localhost:5432` (PostgreSQL: `obs_user`/`obs_password`/`observability`), `localhost:6379` (Redis).
- Assignment: `alert-consumer` writes `alerts` and NEVER sends notifications; `notification-dispatcher` sends notifications across `SLACK`/`TELEGRAM`/`WEBHOOK` (skip `WEBSOCKET`) and NEVER writes `alerts`. No shared DB writes.
- Manual Kafka ACK only. `alert-consumer`: ACK the offset ONLY after the PostgreSQL transaction commits (LLD §10.2 exact wording). On processing failure, do NOT ACK (message redelivers).
- `alert-consumer` must not crash on the two known duplicate-violation cases — treat as idempotent success, ACK, continue.
- Webhook timeouts: RestTemplate connect/read timeout 3s; never let a third-party API exception escape the Kafka listener (must ACK and release cooldown); do not block the Kafka poll thread unreasonably.
- Redis down (`RedisConnectionException`): enter controlled Degraded Mode — log it, still dispatch (duplicates allowed), do NOT bypass cooldown permanently, do NOT crash the consumer.
- Redis cooldown/counter keys: `alert:cooldown:{serviceId}:{environment}:{ruleId}` and `alert:counter:{serviceId}:{environment}:{ruleId}`, both TTL 300s. Increment+expire must be atomic via a single Lua script (never two separate Redis commands). Spec item 3 exact algorithm.
- Delivery only to channels with `enabled = true` and of type `SLACK`/`TELEGRAM`/`WEBHOOK`.
- Every integration test cleans up only rows it created, via namespaced IDs (`test-alert-*`, `test-notif-*`) and `@AfterEach`. NEVER `DELETE FROM alerts` / `alert_rule_channels` without a WHERE. Also clean the Redis keys it created.
- Java 21. Run Maven offline: `-o`. Full reactor verification at completion: `mvn clean test -o`.
- Test channel delivery via WireMock (mocked localhost webhook) — there are no real Slack/Telegram tokens; annotate this in tests and README. Do NOT claim real external integration.

---

## File Structure & Responsibilities

**Modified (existing):**
- `notification-dispatcher/pom.xml` — add `spring-boot-starter-data-jpa`, `postgresql`, `spring-boot-starter-data-redis`, and test deps (`spring-boot-starter-test`, `awaitility`, `wiremock-standalone`). (`platform-db` not needed at runtime here — schema is created by `core-app`; but the shared `ObjectMapper` comes from `platform-common`, already a dependency.)
- `notification-dispatcher/src/main/java/com/thinhbui303/observability/notification/NotificationDispatcherApplication.java` — remove `exclude = {DataSourceAutoConfiguration.class}`.
- `alert-consumer/pom.xml` — add test deps (`spring-boot-starter-test`, `awaitility`). No runtime deps needed beyond what exists (JPA, postgresql, spring-kafka, platform-common already present).
- `README.md` — record the business-key dedup decision and the mocked-webhook caveat.

**Created (alert-consumer):**
- `alert-consumer/src/main/java/com/thinhbui303/observability/alert/entity/AlertEntity.java`
- `alert-consumer/src/main/java/com/thinhbui303/observability/alert/repository/AlertRepository.java`
- `alert-consumer/src/main/java/com/thinhbui303/observability/alert/mapper/AlertMapper.java`
- `alert-consumer/src/main/java/com/thinhbui303/observability/alert/config/KafkaConsumerConfig.java`
- `alert-consumer/src/main/java/com/thinhbui303/observability/alert/service/AlertPersistenceService.java`
- `alert-consumer/src/main/java/com/thinhbui303/observability/alert/kafka/SystemAlertConsumer.java`

**Created (notification-dispatcher):**
- `notification-dispatcher/src/main/java/com/thinhbui303/observability/notification/dto/ChannelTarget.java`
- `notification-dispatcher/src/main/java/com/thinhbui303/observability/notification/config/KafkaConsumerConfig.java`
- `notification-dispatcher/src/main/java/com/thinhbui303/observability/notification/config/HttpClientConfig.java` (RestTemplate bean, 3s timeouts)
- `notification-dispatcher/src/main/java/com/thinhbui303/observability/notification/config/RedisConfig.java` (RedisTemplate + cooldown Lua script bean)
- `notification-dispatcher/src/main/java/com/thinhbui303/observability/notification/service/ChannelConfigService.java`
- `notification-dispatcher/src/main/java/com/thinhbui303/observability/notification/service/AlertCooldownService.java`
- `notification-dispatcher/src/main/java/com/thinhbui303/observability/notification/client/WebhookClient.java`
- `notification-dispatcher/src/main/java/com/thinhbui303/observability/notification/service/NotificationSender.java`
- `notification-dispatcher/src/main/java/com/thinhbui303/observability/notification/kafka/NotificationDispatcher.java`

**Created (tests):**
- `alert-consumer/src/test/resources/application-test.yml`
- `alert-consumer/src/test/java/com/thinhbui303/observability/alert/AlertConsumerIntegrationTest.java`
- `notification-dispatcher/src/test/resources/application-test.yml`
- `notification-dispatcher/src/test/java/com/thinhbui303/observability/notification/NotificationDispatcherIntegrationTest.java`

---

## Field Mapping: `CanonicalAlertEvent` -> `AlertEntity` (alerts)

| CanonicalAlertEvent | AlertEntity (alerts) | Note |
|---|---|---|
| `alertId` | `id` | String PK |
| `ruleId` | `ruleId` | |
| `serviceId` | `serviceId` | FK -> services(id); `serviceName` in the Kafka event IS `services.id` (verified) |
| `environment` | `environment` | |
| `windowStart` | `windowStart` | |
| `severity` | `severity` | |
| `status` | `status` | default `TRIGGERED` on insert |
| `triggeredAt` | `triggeredAt` | (the prompt maps `createdAt`; the canonical field is `triggeredAt`) |
| (none) | `acknowledgedAt` | null |
| `resolvedAt` | `resolvedAt` | often null |
| `occurrenceCount` | `occurrenceCount` | default 1 |

**Explicit decision (record in `AlertMapper` comment + README):** `description`, `triggerValue`, `windowSeconds`, `triggerLogId`, `notificationChannels` have NO column in `alerts`; they serve display/notification only and are intentionally not persisted. Do not add columns.

---

### Task 1: alert-consumer entity, repository, mapper, kafka config

**Files:**
- Create: `alert-consumer/src/main/java/com/thinhbui303/observability/alert/entity/AlertEntity.java`
- Create: `alert-consumer/src/main/java/com/thinhbui303/observability/alert/repository/AlertRepository.java`
- Create: `alert-consumer/src/main/java/com/thinhbui303/observability/alert/mapper/AlertMapper.java`
- Create: `alert-consumer/src/main/java/com/thinhbui303/observability/alert/config/KafkaConsumerConfig.java`
- Modify: `alert-consumer/pom.xml`
- Test: `AlertConsumerIntegrationTest.java` (Task 3)

**Interfaces:**
- Consumes: `CanonicalAlertEvent` (platform-common), `JsonConfig.ObjectMapper` (platform-common), `alerts` schema (platform-db V4).
- Produces: `AlertEntity`, `AlertRepository`, `AlertMapper.toEntity(CanonicalAlertEvent)`, `KafkaConsumerConfig` (consumer factory).

- [ ] **Step 1: Write the four Java files + `jdbc:postgresql` config wiring**

`AlertEntity`:
```java
@Entity
@Table(name = "alerts")
public class AlertEntity {
    @Id
    @Column(name = "id") private String id;
    @Column(name = "rule_id") private Long ruleId;
    @Column(name = "service_id") private String serviceId;
    @Column(name = "environment") private String environment;
    @Column(name = "window_start") private Instant windowStart;
    @Column(name = "severity") private String severity;
    @Column(name = "status") private String status;
    @Column(name = "triggered_at") private Instant triggeredAt;
    @Column(name = "acknowledged_at") private Instant acknowledgedAt;
    @Column(name = "resolved_at") private Instant resolvedAt;
    @Column(name = "occurrence_count") private int occurrenceCount;
    // getters/setters
}
```

`AlertMapper` (encode the full field-mapping table above; include the "no display columns" comment):
```java
@Component
public class AlertMapper {
    public AlertEntity toEntity(CanonicalAlertEvent e) {
        AlertEntity a = new AlertEntity();
        a.setId(e.alertId());
        a.setRuleId(e.ruleId());
        a.setServiceId(e.serviceId());
        a.setEnvironment(e.environment());
        a.setWindowStart(e.windowStart());
        a.setSeverity(e.severity());
        a.setStatus(e.status() != null ? e.status() : "TRIGGERED");
        a.setTriggeredAt(e.triggeredAt());
        a.setAcknowledgedAt(null);
        a.setResolvedAt(e.resolvedAt());
        a.setOccurrenceCount(e.occurrenceCount() != null && e.occurrenceCount() > 0 ? e.occurrenceCount() : 1);
        return a;
    }
}
```

`KafkaConsumerConfig` (mirror `indexer-worker` config; single-record/manual-ack listeners, String value serde — parse JSON in the listener):
```java
@Configuration
public class KafkaConsumerConfig {
    @Value("${spring.kafka.bootstrap-servers}") private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, String> alertConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> alertKafkaListenerContainerFactory() {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(alertConsumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setConcurrency(3);
        return factory;
    }
}
```

POM: add only test deps to `alert-consumer/pom.xml` (`spring-boot-starter-test`, `awaitility`). No runtime change.

- [ ] **Step 2: Compile** — Run: `mvn -q -o -pl alert-consumer -am compiler:compile`. Expected: BUILD SUCCESS / no errors.
- [ ] **Step 3: Commit**
```bash
git add alert-consumer/src/main/java alert-consumer/pom.xml
git commit -m "feat(alert-consumer): entity, mapper, repository, kafka config"
```

---

### Task 2: alert-consumer idempotent persistence service + listener

**Files:**
- Create: `alert-consumer/src/main/java/com/thinhbui303/observability/alert/service/AlertPersistenceService.java`
- Create: `alert-consumer/src/main/java/com/thinhbui303/observability/alert/kafka/SystemAlertConsumer.java`
- Test: `AlertConsumerIntegrationTest.java` (Task 3)

**Interfaces:**
- Consumes: `AlertMapper`, `AlertRepository`, `AlertEntity` (Task 1).
- Produces: `AlertPersistenceService.persist(CanonicalAlertEvent)` and `SystemAlertConsumer` (@KafkaListener on `system-alerts`).

- [ ] **Step 1: Write the persistence service**

```java
@Service
public class AlertPersistenceService {

    private final AlertRepository repo;
    private final AlertMapper mapper;
    private final JdbcTemplate jdbcTemplate;

    // Business-key dedup decision (LLD 10.2/11.3): A single (rule_id, service_id,
    // environment, window_start) IS one business alert. A duplicate emit with a new
    // alertId is the same alert -> we increment occurrence_count instead of inserting.
    // An exact duplicate alertId is fully idempotent -> no write, treated as success.
    @Transactional
    public void persist(CanonicalAlertEvent event) {
        try {
            repo.save(mapper.toEntity(event));
        } catch (DataIntegrityViolationException ex) {
            // (a) exact duplicate alertId -> already persisted, nothing to do
            // (b) uq_alerts_business_key violated by a different alertId ->
            //     bump occurrence_count on the existing row
            int updated = jdbcTemplate.update(
                "UPDATE alerts SET occurrence_count = occurrence_count + 1 " +
                "WHERE rule_id = ? AND service_id = ? AND environment = ? AND window_start = ?",
                event.ruleId(), event.serviceId(), event.environment(), event.windowStart());
            if (updated == 0) {
                // Could be pure duplicate alertId; still idempotent success.
                // Log and swallow — never let this path fail the consume/ack.
                log.info("Alert already persisted, idempotent skip: {}", event.alertId());
            }
        }
    }
}
```

- [ ] **Step 2: Write the Kafka listener**

```java
@Component
public class SystemAlertConsumer {
    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final AlertPersistenceService service;

    @KafkaListener(topics = "system-alerts", groupId = "alert-persistence-group",
                   containerFactory = "alertKafkaListenerContainerFactory")
    public void onAlert(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        CanonicalAlertEvent event = JSON.readValue(record.value(), CanonicalAlertEvent.class);
        service.persist(event);          // local Postgres transaction commits here
        ack.acknowledge();               // ONLY after commit (LLD 10.2)
    }
}
```

- [ ] **Step 3: Compile** — `mvn -q -o -pl alert-consumer -am compiler:compile`. Expected: SUCCESS.
- [ ] **Step 4: Commit**
```bash
git add alert-consumer/src/main/java
git commit -m "feat(alert-consumer): idempotent persistence + system-alerts listener"
```

---

### Task 3: alert-consumer integration test

**Files:**
- Create: `alert-consumer/src/test/resources/application-test.yml`
- Create: `alert-consumer/src/test/java/com/thinhbui303/observability/alert/AlertConsumerIntegrationTest.java`

**Interfaces:**
- Consumes: `AlertPersistenceService`, `SystemAlertConsumer` (Task 2 label), `system-alerts` topic (JSON).

- [ ] **Step 1: test profile**

`application-test.yml`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/observability
    username: obs_user
    password: obs_password
    driver-class-name: org.postgresql.Driver
  kafka:
    bootstrap-servers: localhost:9092
```

- [ ] **Step 2: Write the integration test** (publish via raw `KafkaProducer<String,String>` with JSON `objectMapper.writeValueAsString(event)`; no Testcontainers; assert via `JdbcTemplate`/`AlertRepository`).

Cover exactly:
1. `testAlertPersistence_ShouldWriteToPostgreSQL` — publish 1 valid event, assert exactly 1 row in `alerts`, correct mapping of `serviceId`/`environment`/`windowStart`.
2. `testDuplicateAlertId_ShouldBeIdempotent` — publish same `alertId` twice, assert 1 row, both ACKs OK (wait then poll DB).
3. `testDuplicateWindowEmit_ShouldIncrementOccurrenceCount` — publish 2 events, DIFFERENT `alertId`, SAME `(ruleId, serviceId, environment, windowStart)`; assert 1 row and `occurrence_count == 2`.

(Use namespaced ids: `serviceId = "test-alert-svc" + UUID`, `ruleId` referencing a seeded test rule with that service, ids `test-alert-<uuid>`. Cleanup in `@AfterEach`: `DELETE FROM alerts WHERE id LIKE 'test-alert-%'`, `DELETE FROM service_api_keys WHERE service_id LIKE 'test-alert-svc-%'`, `DELETE FROM services WHERE id LIKE 'test-alert-svc-%'`. Wait for consumer processing using Awaitility up to ~15s.)

- [ ] **Step 3: Run** — `mvn -o -pl alert-consumer test`. Expected: 3 tests pass.
- [ ] **Step 4: Commit**
```bash
git add alert-consumer/src/test
git commit -m "test(alert-consumer): persistence integration tests"
```

---

### Task 4: notification-dispatcher infra + channel config

**Files:**
- Modify: `notification-dispatcher/pom.xml`
- Modify: `notification-dispatcher/src/main/java/.../NotificationDispatcherApplication.java`
- Create: `.../notification/dto/ChannelTarget.java`
- Create: `.../notification/config/KafkaConsumerConfig.java`
- Create: `.../notification/config/HttpClientConfig.java`
- Create: `.../notification/config/RedisConfig.java`
- Create: `.../notification/service/ChannelConfigService.java`
- Test: `NotificationDispatcherIntegrationTest.java` (Task 7)

**Interfaces:**
- Produces: `ChannelTarget(ChannelType type, String target, boolean enabled)`, `KafkaConsumerConfig` (factory, group `notification-dispatcher-group`), `HttpClientConfig.restTemplate()`, `RedisConfig.redisTemplate()` + `cooldownScript`, `ChannelConfigService.findTargets(Long ruleId): List<ChannelTarget>` (filters `enabled == true`, excludes `WEBSOCKET`).

- [ ] **Step 1: POM + application change**

POM: on top of existing `spring-boot-starter-web`, `spring-kafka`, `platform-common`, add `spring-boot-starter-data-jpa`, `postgresql` (runtime), `spring-boot-starter-data-redis`. Test: `spring-boot-starter-test`, `awaitility`, `org.wiremock:wiremock-standalone`.

`NotificationDispatcherApplication.java`:
```java
@SpringBootApplication
public class NotificationDispatcherApplication { ... }
```
(remove the `exclude = {DataSourceAutoConfiguration.class}`)

- [ ] **Step 2: config beans**

`KafkaConsumerConfig` — same pattern as alert-consumer but group `notification-dispatcher-group`; `ChannelConfigService`:
```java
@Component
public class ChannelConfigService {
    private final JdbcTemplate jdbcTemplate;
    public List<ChannelTarget> findTargets(Long ruleId) {
        return jdbcTemplate.query(
            "SELECT channel_type, target, enabled FROM alert_rule_channels " +
            "WHERE alert_rule_id = ? AND enabled = TRUE AND channel_type IN ('SLACK','TELEGRAM','WEBHOOK')",
            (rs, i) -> new ChannelTarget(
                ChannelType.valueOf(rs.getString("channel_type")),
                rs.getString("target"),
                rs.getBoolean("enabled")), ruleId);
    }
}
```
`HttpClientConfig` — `RestTemplate` with connect/read timeout 3000ms.
`RedisConfig` — `RedisTemplate<String,String>` (String key/value serializer) + a `@Bean DefaultRedisScript<Long> incrementCooldownScript()` returning the Lua body (defined in Task 5). Skip declaring the script as a bean here if simpler to build inline in the cooldown service; prefer a single shared script bean referenced by name.

- [ ] **Step 3: Compile** — `mvn -q -o -pl notification-dispatcher -am compiler:compile`. Expected: SUCCESS.
- [ ] **Step 4: Commit**
```bash
git add notification-dispatcher/pom.xml notification-dispatcher/src/main/java
git commit -m "feat(notification): dispatcher infra, channel config, kafka/redis/http config"
```

---

### Task 5: notification-dispatcher cooldown service (Redis, Lua, degraded mode)

**Files:**
- Create: `.../notification/service/AlertCooldownService.java`
- Test: `NotificationDispatcherIntegrationTest.java` (Task 7)

**Interfaces:**
- Consumes: `RedisTemplate<String,String>`, cooldown Lua script.
- Produces: `AlertCooldownDecision acquire(CanonicalAlertEvent): (boolean shouldSend, Long suppressedCount)` implementing spec item 3.

- [ ] **Step 1: Implement cooldown service**

```java
@Service
public class AlertCooldownService {

    private static final long COOLDOWN_TTL_SECONDS = 300;
    private static final String COOLDOWN_SCRIPT =
      "if redis.call('INCR', KEYS[1]) == 1 then " +
      "  redis.call('EXPIRE', KEYS[1], ARGV[1]) " +
      "end " +
      "return redis.call('GET', KEYS[1])";

    private final RedisTemplate<String, String> redis;

    public AlertCooldownService(RedisTemplate<String, String> redis) {
        this.redis = redis;
    }

    /** Acquire cooldown lock. Returns true = first alert in window -> send;
     *  false = suppressed (counter incremented). Throws RedisConnectionException
     *  so the caller can enter degraded mode. */
    public boolean acquire(CanonicalAlertEvent e) {
        String cooldownKey = cooldownKey(e);
        String counterKey  = counterKey(e);

        Boolean acquired = redis.opsForValue()
            .setIfAbsent(cooldownKey, "LOCKED", Duration.ofSeconds(COOLDOWN_TTL_SECONDS));
        if (Boolean.TRUE.equals(acquired)) {
            return true; // first alert: we own the lock
        }
        // Cooldown already held -> increment counter atomically (INCR+EXPIRE in one Lua)
        redis.execute(...Lua..., List.of(counterKey, cooldownKey), String.valueOf(COOLDOWN_TTL_SECONDS));
        return false;
    }

    /** Release the cooldown lock after a FAILED send. */
    public void release(CanonicalAlertEvent e) {
        redis.delete(cooldownKey(e));
    }

    private String cooldownKey(CanonicalAlertEvent e) {
        return "alert:cooldown:" + e.serviceId() + ":" + e.environment() + ":" + e.ruleId();
    }
    private String counterKey(CanonicalAlertEvent e) {
        return "alert:counter:" + e.serviceId() + ":" + e.environment() + ":" + e.ruleId();
    }
}
```

Notes to implement:
- Use the appropriate Redis Lua execution method available on `RedisTemplate` (e.g. a `RedisScript` executed via `redisTemplate.execute(script, keys, args)`). The script atomically INCR counter and sets TTL only on first increment, per LLD §6.2.
- On success-send, keep cooldown key (no delete). On failed-send, caller calls `release(..)` to delete the cooldown key so the next alert can retry (LLD §6.2, SDD §4.3).
- If `RedisConnectionException` propagates, the caller (Task 6) enters degraded mode.

- [ ] **Step 2: Compile** — `mvn -q -o -pl notification-dispatcher -am compiler:compile`. Expected: SUCCESS.
- [ ] **Step 3: Commit**
```bash
git add notification-dispatcher/src/main/java
git commit -m "feat(notification): redis cooldown service with atomic lua + degraded mode hook"
```

---

### Task 6: webhook client + sender + dispatcher orchestrator (degraded mode)

**Files:**
- Create: `.../notification/client/WebhookClient.java`
- Create: `.../notification/service/NotificationSender.java`
- Create: `.../notification/kafka/NotificationDispatcher.java`
- Test: `NotificationDispatcherIntegrationTest.java` (Task 7)

**Interfaces:**
- Consumes: `AlertCooldownService`, `ChannelConfigService`, `RestTemplate`, `ChannelTarget`.
- Produces: `NotificationSender.sendAll(CanonicalAlertEvent, List<ChannelTarget>)`, `NotificationDispatcher` (@KafkaListener on `system-alerts`, group `notification-dispatcher-group`).

- [ ] **Step 1: WebhookClient**

```java
@Component
public class WebhookClient {
    private final RestTemplate rest;
    public WebhookClient(RestTemplate rest) { this.rest = rest; }

    public void send(ChannelTarget target, CanonicalAlertEvent event) {
        Map<String, Object> body = new HashMap<>();
        body.put("text", buildSlackText(event));
        rest.postForEntity(target.target(), body, String.class); // throws on 4xx/5xx/timeout
    }
    private String buildSlackText(CanonicalAlertEvent e) {
        return String.format("[%s] alert %s on %s/%s rule=%s severity=%s",
            e.severity(), e.alertId(), e.serviceId(), e.environment(), e.ruleId(), e.status());
    }
}
```

- [ ] **Step 2: NotificationSender** — iterate `List<ChannelTarget>`, call `WebhookClient.send` for each; wrap per-channel so one channel failure does not abort others; return a `List<ChannelTarget>` of the ones that failed (for the caller to decide release). Use the 3s-timeout RestTemplate; catch `ResourceAccessException`/`HttpClientErrorException` per channel and record as failure (do not throw out of sendAll).

- [ ] **Step 3: NotificationDispatcher** with degraded mode:

```java
@Component
public class NotificationDispatcher {
    Logger log = ...;
    private final ChannelConfigService channels;
    private final AlertCooldownService cooldown;
    private final NotificationSender sender;

    @KafkaListener(topics = "system-alerts", groupId = "notification-dispatcher-group",
                   containerFactory = "notificationKafkaListenerContainerFactory")
    public void onAlert(ConsumerRecord<String, String> record, Acknowledgment ack) {
        boolean ackNow = true;
        try {
            CanonicalAlertEvent event = JSON.readValue(record.value(), CanonicalAlertEvent.class);
            List<ChannelTarget> targets = channels.findTargets(event.ruleId());
            if (targets.isEmpty()) { ackNow = true; return; }

            boolean shouldSend;
            try {
                shouldSend = cooldown.acquire(event);
            } catch (RedisConnectionException ex) {
                // Degraded mode (SDD 4.2/4.3): Redis down — do not crash, do not
                // permanently bypass cooldown. Duplicates are temporarily accepted.
                log.warn("Redis down; notification-degraded mode (duplicates may be sent)");
                shouldSend = true;
            }

            if (shouldSend) {
                var failed = sender.sendAll(event, targets);
                if (failed.isEmpty()) {
                    // success: keep cooldown (do nothing)
                } else {
                    cooldown.release(event); // send failed -> release lock so retry possible
                }
            } else {
                log.info("Alert {} suppressed (within cooldown)", event.alertId());
            }
        } catch (Exception ex) {
            log.error("Error dispatching notification, leaving un-acked for redelivery", ex);
            ackNow = false;
        } finally {
            if (ackNow) ack.acknowledge();
        }
    }
}
```

- [ ] **Step 4: Compile** — `mvn -q -o -pl notification-dispatcher -am compiler:compile`. Expected: SUCCESS.
- [ ] **Step 5: Commit**
```bash
git add notification-dispatcher/src/main/java
git commit -m "feat(notification): webhook sender + dispatcher with redis degraded mode"
```

---

### Task 7: notification-dispatcher integration tests (8 cases)

**Files:**
- Create: `notification-dispatcher/src/test/resources/application-test.yml`
- Create: `notification-dispatcher/src/test/java/com/thinhbui303/observability/notification/NotificationDispatcherIntegrationTest.java`

**Interfaces:**
- Consumes: full dispatcher stack (Tasks 4-6), `alerts`-adjacent `alert_rules`/`alert_rule_channels` seeding (NOT `alerts` table).

- [ ] **Step 1: test profile**

`application-test.yml`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/observability
    username: obs_user
    password: obs_password
    driver-class-name: org.postgresql.Driver
  kafka:
    bootstrap-servers: localhost:9092
  data:
    redis:
      host: localhost
      port: 6379
```

- [ ] **Step 2: Write the test harness**

Constants: `BOOTSTRAP = "localhost:9092"`.
Seed helpers (in `@BeforeEach` per test or a private method): insert a `services` row (`id = "test-notif-svc<uuid>"`), an `alert_rules` row (returns `ruleId`), and `alert_rule_channels` rows (the ones the case needs). Cleanup in `@AfterEach`:
```sql
DELETE FROM alerts WHERE id LIKE 'test-notif-%';        -- only rows this test created
DELETE FROM alert_rule_channels WHERE alert_rule_id IN (SELECT id FROM alert_rules WHERE rule_name LIKE 'test-notif-%');
DELETE FROM alert_rules WHERE rule_name LIKE 'test-notif-%';
DELETE FROM services WHERE id LIKE 'test-notif-svc%';
```
Also `redisTemplate.delete(keysOfThisTest)` for the cooldown/counter the test created.

The dispatcher is a separate Spring Boot app; the test is a `@SpringBootTest` in the same module so the `@KafkaListener` is live, and uses its own `@Value`-configured consumer to read webhook effect. For asserting webhook calls, use a `@BeforeAll` WireMock server bound to a random port; the `alert_rule_channels.target` for that rule points at the WireMock URL.

- [ ] **Step 3: Implement the 8 cases**

1. `testNotification_FirstAlert_ShouldAcquireLockAndSend` — first alert for a `(serviceId, environment, ruleId)`; mock webhook returns 200; assert `NotificationSender`/WireMock POST received, then cooldown Redis key `alert:cooldown:{svc}:{env}:{rule}` exists.
2. `testNotification_WithinCooldown_ShouldSuppressAndIncrementCounter` — after case 1 (lock held), publish a second alert same key; assert no more webhook POSTs, counter key incremented to >= 1 (and stays <= a small bound).
3. `testNotification_SendFailure_ShouldReleaseLockImmediately` — mock webhook returns 500/timeout; assert the cooldown key is deleted after the failure, so a subsequent alert (published right after) can be sent (WireMock count == 2, now 200).
4. `testNotification_ChannelDisabled_ShouldBeSkipped` — seed 2 channels, one `enabled=false`; assert only the enabled channel receives the POST.
5. `testNotification_WebsocketChannel_Skipped` — seed a `WEBSOCKET` channel `enabled=true` + one `SLACK`; assert only SLACK receives POST, websocket ignored.
6. `testNotification_NoEnabledChannels_ShouldNotSend` — channel `enabled=false`; assert NO webhook POST.
   (Spec lists 8 cases in the prompt; cases 4-5 map to channel skip; merge/align numbering to the prompt's exact list: use the prompt's case numbers 4=ChannelDisabled, 5=within-cooldown, 6=send-failure, 7=ChannelDisabled variant, 8=RedisDown. Adjust naming accordingly but cover at minimum: first-send-lock, within-cooldown-suppress+increment, send-failure-release, channel-disabled-skip, websocket-skip, no-enabled-channels-no-send, redis-down-degrade. The prompt's 8 are the checklist; satisfy all 8 enumerated behaviors.)
7. `testNotification_RedisDown_ShouldDegradeGracefully` — point app at a dead Redis port (override `spring.data.redis.port` to an unused port, e.g. 6399) OR stop container; assert consumer doesn't crash, alert still delivered (webhook POST received), and no ACK hang (message not stuck). Verify via a follow-up message being processed.
8. (Prompt case 8 = RedisDown as above; map remaining.) Implement the exact 8 test names from the prompt: `testAlertPersistence_*` are in the consumer test (Task 3); here implement:
   - `testNotification_FirstAlert_ShouldAcquireLockAndSend`
   - `testNotification_WithinCooldown_ShouldSuppressAndIncrementCounter`
   - `testNotification_SendFailure_ShouldReleaseLockImmediately`
   - `testNotification_ChannelDisabled_ShouldBeSkipped`
   - `testNotification_WebsocketChannel_ShouldBeSkipped`
   - `testNotification_NoEnabledChannels_ShouldNotSend`
   - `testRedisDown_ShouldDegradeGracefully`
   Ensure all 8 behaviors from the prompt are present (combine the 3 channel-skip variants into covering the `enabled=false` + `WEBSOCKET` cases).

- [ ] **Step 4: Run** — `mvn -o -pl notification-dispatcher test`. Expected: all pass. (WireMock port in tests is fixed per run; ensure the seeded channel targets point at the live WireMock URL.)
- [ ] **Step 5: Commit**
```bash
git add notification-dispatcher/src/test
git commit -m "test(notification): dispatcher integration tests (cooldown/deg/modes)"
```

---

### Task 8: README updates + full-reactor verification

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update README** — record (a) the business-key dedup decision (unique `(ruleId, serviceId, environment, windowStart)`, `occurrence_count` bump on duplicate emit) and (b) the mocked-webhook caveat (WireMock; no real Slack/Telegram tokens). Add regression note that full reactor must stay green: `mvn clean test -o`.
- [ ] **Step 2: Full reactor test** — `mvn clean test -o`. Expected: `BUILD SUCCESS`; all prior module counts preserved (platform-common 1, ingestion-service 5, indexer-worker 12, analytics-engine 6, core-app 8) plus alert-consumer 3 and notification-dispatcher 8.
- [ ] **Step 3: Verify no infra left dirty** — confirm the DB has no `test-alert-*`/`test-notif-*` rows and Redis has no `alert:cooldown:*`/`alert:counter:*` test keys left behind; if so, clean them.
- [ ] **Step 4: Commit**
```bash
git add README.md
git commit -m "docs: record alert-consumer dedup decision + mocked-webhook caveat; slice4 done"
```

---

## Slice 4 Completion Report (fill in all items with evidence)

- Full react `mvn clean test -o` result (expect `BUILD SUCCESS`), plus per-module test counts (no regressions on prior slices).
- Migration used: **none added** — `V4__create_alerts.sql` already carries `environment`, `window_start`, `occurrence_count`, `uq_alerts_business_key`. List the confirmed migration files `V1`..`V7`.
- `serviceName` == `services.id` evidence: point to `ApiKeyValidator.java:25-34`, `CanonicalEventBuilder.java:28`, `ErrorWindowStreamTopology.java:53`, `PatternMatchStreamTopology.java:40`.
- Unique-constraint decision: **YES**, unique `(rule_id, service_id, environment, window_start)` (already enforced by `V4`); duplicate emit -> `occurrence_count++`; exact duplicate alertId -> idempotent skip. Where recorded: `AlertPersistenceService` comment, README.
- Slack/Telegram webhook: **mocked with WireMock** in tests; no live tokens; documented in README.