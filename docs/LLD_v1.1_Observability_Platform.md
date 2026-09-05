**TÀI LIỆU THIẾT KẾ CHI TIẾT**

**(LOW-LEVEL DESIGN – LLD)**

_Enterprise Log Aggregation & Real-time Observability Platform_

Tham chiếu: SDD v1.1 & SRS v2.1

**Phiên bản LLD v1.1 — Implementation Ready**

_Đã áp dụng 7 điểm blocker bắt buộc và 20 điểm nên sửa trước khi code từ vòng review Senior Backend Engineer_

# **Lịch sử thay đổi tài liệu**

| **Phiên bản** | **Trạng thái**   | **Mô tả**                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| ------------- | ---------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| v1.0          | Draft            | Bản LLD sơ thảo dựa trên SDD v1.1: module structure, entity, DTO, service layer, Kafka, Redis, Elasticsearch, API contract, sequence diagram, concurrency, test strategy.                                                                                                                                                                                                                                                                                                                                                                                                   |
| v1.1          | 🟢 Ready to Code | Sửa 7 điểm 🔴 bắt buộc (producer idempotency, index theo event timestamp, alert persistence idempotent, retry consumer flow, retry/DLQ ACK ordering, service identity từ API Key, Elasticsearch metadata mapping) và 20 điểm 🟠 nên sửa trước code (Instant timestamp, cooldown key theo environment, counter TTL, event-time windowing, search_after, Kafka config baseline, executor strategy, test matrix theo component, schemaVersion, validation error schema, API key redaction, alert dedup business key, entity FK mapping consistency, và các điểm bổ sung khác). |

_Không tạo LLD v2.0 — theo đúng khuyến nghị của vòng review: các điểm còn lại là tinh chỉnh trong phạm vi v1.1, không phát sinh thay đổi kiến trúc so với SDD v1.1._

# **1\. Project & Module Structure**

Giữ nguyên cấu trúc Multi-Module Maven/Gradle theo SDD v1.1 (không đổi so với v1.0 — đánh giá tốt). Bổ sung làm rõ trách nhiệm RetryWorker bên trong indexer-worker (mục 5.3) theo yêu cầu chốt rõ retry consumer flow.

```
observability-platform/
├── pom.xml
├── platform-common/            DTO, Enums, Canonical Schema, Shared Exceptions
│   └── .../common/
│       ├── domain/             Canonical LogEvent, AlertEvent (kèm schemaVersion)
│       ├── dto/                Standard ApiResponse, ErrorResponse (kèm fieldErrors)
│       ├── exception/          BaseException, AuthenticationException, RateLimitException
│       └── util/                DateTimeUtils, MaskingUtils
├── ingestion-service/          High-throughput Ingestion Worker (Java 21 Virtual Threads)
│   └── .../ingestion/
│       ├── config/             KafkaProducerConfig, RedisConfig, SecurityConfig
│       ├── controller/         LogIngestionController
│       ├── service/            IngestionService, DataMaskingService, RateLimiterService
│       ├── kafka/               KafkaLogProducer
│       ├── ratelimit/           TokenBucketRateLimiter, LocalBucketFallbackService
│       └── security/            ApiKeyAuthenticationFilter, ApiKeyValidator
├── indexer-worker/             Kafka Consumer -> Elasticsearch Bulk Ingestion
│   └── .../indexer/
│       ├── config/              KafkaConsumerConfig, ElasticsearchConfig
│       ├── consumer/            KafkaLogConsumer
│       ├── processor/           BatchLogProcessor
│       ├── es/                  ElasticsearchBulkIndexer, IndexNameResolver (event-time based)
│       ├── handler/             ItemResultHandler, RetryPublisher, DlqPublisher
│       └── retry/               RetryWorker, RetryPolicy   (mục 5.3 — chốt rõ so với v1.0)
├── analytics-engine/           Kafka Streams Engine (Event-time windowing + grace period)
│   └── .../analytics/
│       ├── config/               KafkaStreamsConfig
│       └── topology/             ErrorWindowStreamTopology
├── alert-consumer/             Worker độc lập ghi nhận Alert vào PostgreSQL (idempotent)
│   └── .../alertconsumer/
│       ├── consumer/             SystemAlertConsumer
│       └── service/              AlertPersistenceService
├── notification-dispatcher/    Worker độc lập xử lý Slack/Telegram + Redis Lock
│   └── .../notification/
│       ├── dispatcher/           NotificationDispatcher
│       ├── channel/              SlackNotificationChannel, TelegramNotificationChannel
│       └── cooldown/             AlertCooldownService
└── core-app/                   Management REST APIs & WebSocket Dashboard Server
    └── .../core/
        ├── config/               SecurityConfig, WebSocketConfig, JPAConfig
        ├── controller/           AuthController, ServiceRegistryController, AlertRuleController, LogQueryController
        ├── entity/               JPA Entities (User, Role, Service, ServiceApiKey, AlertRule, ...)
        ├── repository/           Spring Data JPA Repositories
        ├── service/              AuthService, ServiceRegistryService, LogQueryService, AuditLogService
        └── websocket/            DashboardWebSocketHandler, StompPublisher
```

# **2\. Entity & Data Layer Design (RDBMS – PostgreSQL)**

ERD giữ nguyên theo SDD v1.1. Chi tiết mapping Java Entity được hiệu chỉnh ở các điểm sau.

## **2.1. Chi tiết Java Entities & Mapping Rules**

### **UserEntity (users)**

- id: Long (@Id, @GeneratedValue(strategy = GenerationType.IDENTITY))
- username: String (nullable=false, unique=true, length=50)
- passwordHash: String (nullable=false, length=255)
- createdAt: Instant (nullable=false, updatable=false)
- @ManyToMany qua bảng trung gian user_roles liên kết RoleEntity.

### **RoleEntity (roles)**

- id: Integer (@Id, @GeneratedValue(strategy = GenerationType.IDENTITY))
- name: RoleName (Enum: ADMIN, DEVOPS, DEVELOPER, VIEWER)
- description: String (length=255)

### **ServiceEntity (services)**

- id: String (@Id, length=50) — ví dụ "payment-service"
- name: String (nullable=false, length=100)
- teamOwner: String (nullable=false, length=50)
- environment: String (nullable=false, length=20)
- status: ServiceStatus (Enum: ACTIVE, DISABLED)
- createdAt: Instant (nullable=false)

### **ServiceApiKeyEntity (service_api_keys)**

- id: Long (@Id, @GeneratedValue(strategy = GenerationType.IDENTITY))
- service: ServiceEntity (@ManyToOne(fetch=LAZY), @JoinColumn(name="service_id"), nullable=false)
- keyPrefix: String (nullable=false, length=12)
- keyHash: String (nullable=false, length=255) — SHA-256
- createdAt: Instant (nullable=false)
- expiresAt: Instant (nullable=true)
- revokedAt: Instant (nullable=true)
- lastUsedAt: Instant (nullable=true)

### **AlertRuleEntity (alert_rules) — đã sửa mapping FK**

**Sửa (nên sửa trước code):** v1.0 khai báo song song cả "serviceId: String" và annotation @ManyToOne cho cùng một khóa ngoại — không nhất quán. Chốt dùng đúng một cách: field quan hệ đối tượng qua @ManyToOne, không giữ thêm field String serviceId thô trừ khi có lý do rõ ràng (ví dụ tối ưu đọc), đồng bộ với ERD.

- id: Long (@Id, @GeneratedValue(strategy = GenerationType.IDENTITY))
- ruleName: String (nullable=false, length=100)
- service: ServiceEntity (@ManyToOne(fetch=LAZY), @JoinColumn(name="service_id"))
- environment: String (nullable=false, length=20)
- conditionType: ConditionType (Enum: ERROR_SPIKE, PATTERN_MATCH — semantics chốt tại mục 11.1)
- thresholdValue: Integer (nullable=false)
- windowSeconds: Integer (nullable=false)
- severity: Severity (Enum: CRITICAL, HIGH, MEDIUM)
- isEnabled: Boolean (nullable=false, default=true)
- createdBy: UserEntity (@ManyToOne(fetch=LAZY), @JoinColumn(name="created_by") — FK tới users.id, đúng)
- createdAt: Instant (nullable=false)
- @OneToMany(mappedBy="alertRule", cascade=CascadeType.ALL, orphanRemoval=true) tới AlertRuleChannelEntity

**Sửa (nên sửa trước code):** cascade = ALL chỉ giữ ở đúng một quan hệ này (AlertRule → AlertRuleChannel), vì lifecycle của channel hoàn toàn phụ thuộc AlertRule (xóa rule thì xóa luôn channel con). Tuyệt đối KHÔNG áp dụng CascadeType.ALL tràn lan sang các quan hệ khác (ví dụ Alert → Service, Alert → User) — các entity đó có lifecycle độc lập, cascade rộng có thể gây xóa nhầm dữ liệu dùng chung.

### **AlertRuleChannelEntity (alert_rule_channels)**

- id: Long (@Id, @GeneratedValue(strategy = GenerationType.IDENTITY))
- alertRule: AlertRuleEntity (@ManyToOne, @JoinColumn(name="alert_rule_id"), nullable=false)
- channelType: ChannelType (Enum: SLACK, TELEGRAM, WEBHOOK, WEBSOCKET)
- target: String (length=255)
- enabled: Boolean (default=true)

### **AlertEntity (alerts) — đã bổ sung ràng buộc chống trùng ở business level**

**Sửa (nên sửa trước code):** ngoài id (alertId — immutable, dùng làm khóa idempotency ở mục 4.1 SDD), bổ sung cân nhắc một unique constraint theo nghiệp vụ (ruleId, serviceId, environment, windowStart) để phát hiện trường hợp Kafka Streams phát lại (emit lại) cùng một alert cho cùng một cửa sổ thời gian. Không bắt buộc phải thêm unique constraint ngay ở MVP nếu nghiệp vụ chấp nhận multiple occurrences hợp lệ trong cùng window — nhưng quyết định này (có hay không) phải được ghi rõ, không để ngỏ.

- id: String (@Id) — UUID-v4, alertId bất biến
- ruleId: Long (nullable=false)
- serviceId: String (nullable=false)
- environment: String (nullable=false) — bổ sung để phục vụ business key chống trùng
- windowStart: Instant (nullable=false) — bổ sung, mốc bắt đầu cửa sổ Tumbling Window sinh ra alert này
- severity: Severity (nullable=false)
- status: AlertStatus (Enum: TRIGGERED, OPEN, ACKNOWLEDGED, RESOLVED)
- triggeredAt: Instant (nullable=false)
- acknowledgedAt: Instant
- resolvedAt: Instant
- occurrenceCount: Integer (default=1)

### **AuditLogEntity (audit_logs) — giữ nguyên**

- id: Long (@Id, @GeneratedValue(strategy = GenerationType.IDENTITY))
- username: String (nullable=false, length=50) — snapshot chuỗi lịch sử, giữ nguyên có chủ đích
- action: String (nullable=false, length=100)
- resourceTarget: String (nullable=false, length=100)
- ipAddress: String (nullable=false, length=45)
- resultStatus: String (nullable=false, length=20) — SUCCESS/FAILED
- createdAt: Instant (nullable=false)

# **3\. DTO & Canonical Event Models**

## **3.1. HTTP Ingestion DTOs (ingestion-service) — đã sửa**

**Sửa (blocker bắt buộc):** v1.0 để timestamp là String tự do (@NotBlank String timestamp) và không cho phép client cung cấp eventId, dẫn đến vấn đề idempotency ở mục 3.2 dưới đây. Sửa: timestamp kiểu Instant (Spring/Jackson deserialize trực tiếp "2026-09-01T18:33:50.123Z" → Instant, có validation định dạng tự động, rõ timezone, không cần parse thủ công); bổ sung trường eventId tùy chọn.

```
// LogIngestionRequest.java
public record LogIngestionRequest(
    String eventId,              // Optional — nếu null, server generate UUID (mục 3.2)
    @NotNull Instant timestamp,  // Sửa: Instant thay vì String tự do
    @NotBlank String level,
    @NotBlank String message,
    String traceId,
    String spanId,
    String host,
    String instanceId,
    String logger,
    String httpMethod,
    String endpoint,
    Integer statusCode,
    Long durationMs,
    Map<String, Object> metadata
) {}

// LogBatchIngestionRequest.java
public record LogBatchIngestionRequest(
    @NotEmpty @Valid List<LogIngestionRequest> logs
) {}
// Lưu ý: mỗi log trong batch có eventId RIÊNG (nếu client cung cấp),
// không dùng chung một Idempotency-Key cho cả batch.
```

Ghi chú bảo mật: serviceName và environment KHÔNG nằm trong DTO này — chi tiết tại mục 4.1 (service identity lấy từ API Key, không lấy từ client payload).

## **3.2. Canonical Event Schema (Kafka Payload) — đã sửa**

**Sửa (blocker bắt buộc):** đây là điểm quan trọng nhất của toàn bộ review: v1.0 luôn generate eventId MỚI bằng UUID.randomUUID() tại Ingestion Service, sau khi request đã tới server. Vấn đề: nếu HTTP response bị timeout SAU KHI Kafka đã ACK thành công, client không biết request đã thành công và sẽ retry — lần retry đó tạo ra một eventId hoàn toàn khác, khiến Elasticsearch không thể coi đây là bản ghi trùng (vì hai eventId khác nhau). Đây là khoảng trống giữa "downstream idempotency" (đã có, ở tầng Elasticsearch \_id) và "producer retry idempotency" (chưa có, ở tầng client-to-server) — nói cách khác Ingestion Service đã tự phá vỡ điều kiện tiên quyết cho cơ chế idempotent mà chính nó thiết kế ở tầng dưới.

Quyết định: cho phép client cung cấp eventId (ổn định qua các lần retry của cùng client). Nếu client không gửi, server generate UUID như cũ. Đây là lựa chọn đơn giản hơn so với việc dùng một header Idempotency-Key riêng biệt, phù hợp bản chất của một log platform (mỗi bản ghi log vốn đã có định danh tự nhiên theo ngữ cảnh phát sinh).

```
CanonicalEventBuilder:
  if (request.eventId() != null && !request.eventId().isBlank())
      eventId = request.eventId();      // Client cung cấp — ổn định qua các lần retry
  else
      eventId = UUID.randomUUID().toString();   // Server generate khi client không gửi

// CanonicalLogEvent.java
public record CanonicalLogEvent(
    int schemaVersion,        // Bổ sung — vd: 1. Cho phép tiến hóa schema an toàn (v1 -> v2)
    String eventId,           // Immutable — từ client hoặc server-generated (xem trên)
    Instant timestamp,        // Nguồn xác định index Elasticsearch (mục 7.1) và event-time windowing (mục 11.2)
    String serviceName,       // Gán từ AuthenticatedServiceIdentity, KHÔNG từ client payload (mục 4.1)
    String environment,       // Gán từ AuthenticatedServiceIdentity, KHÔNG từ client payload (mục 4.1)
    String level,
    String message,
    String traceId,
    String spanId,
    String host,
    String instanceId,
    String logger,
    String httpMethod,
    String endpoint,
    Integer statusCode,
    Long durationMs,
    Map<String, Object> metadata   // Mapping Elasticsearch chốt tại mục 7.3
) {}
```

Khi client retry với cùng eventId: CanonicalEvent mới → Kafka → Indexer Bulk create với cùng \_id → Elasticsearch trả 409 Conflict → xử lý như idempotent success (khớp SDD v1.1 mục 5.2), không tạo bản ghi trùng.

# **4\. Service Layer Architecture**

```
[HTTP Request]
     │
     ▼
[LogIngestionController]
     │
     ▼
[ApiKeyAuthenticationFilter] ──► [ApiKeyValidator] (Cache + SHA-256 DB)
     │                                    │
     │                                    ▼
     │                     AuthenticatedServiceIdentity
     │                     { serviceId, environment }   ◄── nguồn duy nhất
     │                                                        cho serviceName/environment
     ▼
[TokenBucketRateLimiter] ─────► [RedisLuaScriptExecutor] (Fallback: LocalBucket)
     │
     ▼
[IngestionService]
     │
     ├──► [DataMaskingService] (Structured Masking -> Regex Fallback)
     │
     ├──► [CanonicalEventBuilder] (eventId: client-provided hoặc UUID-v4; identity: từ AuthenticatedServiceIdentity)
     │
     └──► [KafkaLogProducer] (kafkaTemplate.send -> Wait Broker ACK)
```

## **4.1. Service Identity — không lấy từ client payload (đã sửa, bắt buộc)**

**Sửa (blocker bắt buộc):** Canonical event có serviceName/environment, nhưng LLD v1.0 không nói rõ hai trường này lấy từ đâu — nếu vô tình lấy trực tiếp từ request body của client thì đây là một lỗ hổng bảo mật/toàn vẹn dữ liệu nghiêm trọng: một service đã xác thực bằng API Key của payment-service vẫn có thể tự khai báo "serviceName": "bank-service" trong payload, làm sai lệch dữ liệu log/alert của một service khác.

Chốt luồng đúng: client CHỈ gửi log payload (không có serviceName/environment trong request). serviceId/environment được xác định DUY NHẤT từ AuthenticatedServiceIdentity — kết quả xác thực API Key — và được gán vào CanonicalLogEvent ở tầng server, độc lập hoàn toàn với dữ liệu client cung cấp. Đây là một ranh giới bảo mật (security boundary), không phải một chi tiết implementation tùy chọn.

## **4.2. Ràng buộc Data Masking — Immutable Boundary (bổ sung)**

**Sửa (nên sửa trước code):** bổ sung nguyên tắc: raw request (trước khi mask) KHÔNG BAO GIỜ được persist dưới bất kỳ hình thức nào, kể cả trong application log của chính Ingestion Service. Nếu Ingestion Service log nguyên văn request body (ví dụ để debug) trước bước Data Masking, chính service này trở thành nơi làm lộ password/token/thẻ tín dụng trước khi kịp che — vô hiệu hóa toàn bộ mục đích của Data Masking.

Luồng bắt buộc: Raw request → NEVER persisted / NEVER logged nguyên văn → Data Masking → CanonicalEvent → Kafka. Access log ở tầng Ingestion Controller chỉ ghi metadata kỹ thuật (status code, latency, serviceId), không ghi request body.

# **5\. Kafka Engineering (Producer, Consumer & DLQ Pipeline)**

## **5.1. Kafka Producer Component (ingestion-service)**

- Key: serviceId (String).
- Value: CanonicalLogEvent (JSON Serialized, kèm schemaVersion).

**Sửa (nên sửa trước code):** phân biệt rõ 2 tầng retry, tránh nhầm lẫn khi code: Kafka Producer retry (retries=3) chỉ xử lý lỗi tạm thời ở mức broker/network do chính Kafka client tự động thử lại nội bộ (ví dụ broker leader election đang diễn ra) — đây KHÔNG phải là application-level retry. Khi Kafka hoàn toàn không khả dụng (ví dụ hết retries, hoặc TimeoutException), lỗi được đẩy lên tầng HTTP dưới dạng 503, và việc retry ở mức đó là trách nhiệm của client/microservice producer (đã quy định tại SDD v1.1 mục 9: max retry=3, exponential backoff, jitter) — đây là application-level retry, một tầng hoàn toàn khác.

Cấu hình baseline (chưa tối ưu — sẽ tinh chỉnh sau khi có kết quả benchmark thực tế):

| **Tham số**                           | **Giá trị baseline**                                                    |
| ------------------------------------- | ----------------------------------------------------------------------- |
| acks                                  | all (min.insync.replicas = 2)                                           |
| retries                               | 3 (Kafka Producer client-side retry, khác application retry — xem trên) |
| enable.idempotence                    | true                                                                    |
| compression.type                      | snappy                                                                  |
| delivery.timeout.ms                   | 30000 (baseline — bổ sung so với v1.0)                                  |
| request.timeout.ms                    | 10000 (baseline — bổ sung)                                              |
| linger.ms                             | 5 (baseline — bổ sung, ưu tiên throughput theo lô nhỏ)                  |
| batch.size                            | 32768 bytes (baseline — bổ sung)                                        |
| max.in.flight.requests.per.connection | 5 (an toàn với enable.idempotence=true — bổ sung)                       |

## **5.2. Indexer Consumer Pipeline (indexer-worker) — đã sửa thứ tự ACK**

**Sửa (blocker bắt buộc):** v1.0 mô tả "Filter Failure Items → publish DLQ/Retry → Commit Sync Offset" nhưng không nêu rõ thứ tự publish và ACK giữa retry/DLQ topic và original topic. Rủi ro: nếu Indexer publish sang logs.retry.1s xong lập tức commit offset của raw-logs mà chưa chắc chắn message retry đã thực sự được Kafka broker ghi nhận (ACK), và ngay sau đó producer gửi retry-topic thất bại, hệ quả là: offset gốc đã bị coi là xử lý xong (committed) NHƯNG message retry lại chưa từng đến nơi — dữ liệu biến mất hoàn toàn (mất dữ liệu thực sự, không chỉ là một item lỗi).

```
[raw-logs Topic]
       │
       ▼
[KafkaLogConsumer] (@KafkaListener, Manual Ack)
       │
       ▼
[BatchLogProcessor] ──► Collect Batch (1,000 items / 500ms)
       │
       ▼
[ElasticsearchBulkIndexer] ──► ES Bulk API (_id = eventId, action = "create")
       │
       ▼
[BulkResponseHandler] — phân loại theo từng item
   ├── 201 Created        → success
   ├── 409 Conflict        → idempotent success (ignore)
   ├── 429 / Timeout        → retryable
   │        │
   │        ▼
   │   [RetryPublisher.send(logs.retry.1s)]
   │        │
   │        ▼
   │   ĐỢI Kafka ACK của retry-topic  (producer.get() / callback thành công)
   │        │
   │        ▼
   │   (chỉ sau khi có ACK trên) mới coi item này "đã xử lý xong"
   │
   └── 400 Bad Schema       → non-retryable
            │
            ▼
       [DlqPublisher.send(logs.dlq)]
            │
            ▼
       ĐỢI Kafka ACK của logs.dlq
            │
            ▼
       (chỉ sau khi có ACK trên) mới coi item này "đã xử lý xong"
       │
       ▼
[Kafka Acknowledgment] ──► Commit offset của raw-logs CHỈ KHI toàn bộ
                            item trong batch đã "xử lý xong" theo đúng
                            nghĩa trên (success / idempotent success /
                            đã ACK ở retry-topic / đã ACK ở logs.dlq)
```

Nguyên tắc tổng quát: producer ACK của retry-topic hoặc logs.dlq luôn phải xảy ra TRƯỚC khi commit offset gốc — không bao giờ commit offset gốc ngay sau khi gọi send() mà chưa xác nhận kết quả gửi.

## **5.3. Retry Worker — chốt rõ luồng xử lý (đã bổ sung, blocker)**

**Sửa (blocker bắt buộc):** v1.0 chỉ có RetryPublisher (phía publish) nhưng không định nghĩa consumer nào đọc logs.retry.1s/5s/30s và xử lý tiếp — nếu không chốt rõ, lúc code rất dễ rơi vào tình trạng "có publisher, không rõ consumer", khiến message nằm im trong các topic retry mãi mãi.

```
RetryWorker (một component độc lập trong indexer-worker/retry/)
  ├── @KafkaListener(topics = "logs.retry.1s")
  ├── @KafkaListener(topics = "logs.retry.5s")
  └── @KafkaListener(topics = "logs.retry.30s")

Luồng xử lý cho mỗi retry message:
  1. Đọc message + Kafka Record Headers (mục 5.4)
  2. Reconstruct lại CanonicalLogEvent gốc từ message value
  3. Gọi lại ElasticsearchBulkIndexer (Bulk create, cùng logic mục 5.2)
       ├── success/409           → ACK retry topic hiện tại (kết thúc, không đi tiếp)
       ├── vẫn lỗi & retryCount < maxAttempts
       │        → publish sang retry-topic KẾ TIẾP (1s → 5s → 30s)
       │        → đợi ACK retry-topic kế tiếp → mới ACK retry-topic hiện tại
       └── vẫn lỗi & đã ở logs.retry.30s (vượt maxAttempts)
                → publish sang logs.dlq → đợi ACK → mới ACK logs.retry.30s

RetryPolicy (cấu hình tập trung, dùng chung bởi Indexer và RetryWorker):
  maxAttempts        = 3
  delays             = [1s, 5s, 30s]     // ứng với 3 retry topic
  retryableErrors    = { ES_CLUSTER_OVERLOAD, ES_TIMEOUT, HTTP_429 }
  nonRetryableErrors = { MAPPING_ERROR, SCHEMA_INVALID, HTTP_400 }
```

## **5.4. Retry Header Specification (giữ nguyên — đã đúng)**

- X-Original-Topic: bytes\[\]
- X-Original-Partition: int
- X-Original-Offset: long
- X-Retry-Count: int
- X-Error-Type: bytes\[\] (ví dụ "ES_CLUSTER_OVERLOAD", "MAPPING_ERROR")
- X-Error-Message: bytes\[\]
- X-First-Failed-At: long (Epoch Millis)

# **6\. Redis Integration Layer**

## **6.1. Token Bucket Rate Limiting (ingestion-service)**

Giữ nguyên thiết kế Lua script atomic + độ phân giải millisecond (đã đúng từ SDD v1.1).

```
@Service
public class RedisRateLimiterService {
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> rateLimitLuaScript;
    private final LocalBucketFallbackService fallbackService;

    public boolean isAllowed(String serviceId, long capacity, long refillRatePerSec) {
        try {
            List<String> keys = List.of("ratelimit:service:" + serviceId);
            long nowMs = System.currentTimeMillis();
            Long result = redisTemplate.execute(
                rateLimitLuaScript, keys,
                String.valueOf(capacity), String.valueOf(refillRatePerSec), String.valueOf(nowMs)
            );
            return result != null && result == 1L;
        } catch (RedisConnectionException ex) {
            return fallbackService.isAllowed(serviceId);  // Local Fallback — scope tại mục 6.1.1
        }
    }
}
```

**Sửa (nên sửa trước code):** dùng System.currentTimeMillis() theo đồng hồ cục bộ của từng instance Ingestion Service — giữa nhiều instance có thể có clock skew (lệch đồng hồ) nhỏ. Với rate limiter thông thường, sai số nhỏ này chấp nhận được và KHÔNG cần phức tạp hóa bằng Redis TIME command ở giai đoạn MVP. Ghi rõ trong tài liệu: clock skew tolerance là acceptable trade-off đã biết, không phải một thiếu sót bị bỏ sót.

### **6.1.1. Local Rate Limiter Fallback — xác định rõ scope (đã sửa)**

**Sửa (blocker bắt buộc):** v1.0 có LocalBucketFallbackService.isAllowed(serviceId) nhưng chưa chốt giá trị limit cụ thể. Chốt tham số và làm rõ một hệ quả kiến trúc quan trọng cần ghi vào tài liệu, tránh hiểu nhầm khi vận hành.

| **Trạng thái Redis**         | **Rate limit áp dụng**                                                        |
| ---------------------------- | ----------------------------------------------------------------------------- |
| Bình thường (Redis khả dụng) | 1000 request/giây — TOÀN CỤC, chia sẻ giữa mọi instance qua Redis             |
| Redis unavailable            | 100 request/giây/instance — LOCAL, không chia sẻ trạng thái giữa các instance |

Hệ quả cần ghi rõ: Local fallback KHÔNG đảm bảo global rate limit. Ví dụ với 5 instance Ingestion Service đang chạy, tổng traffic thực tế có thể lên tới 100 × 5 = 500 request/giây trong lúc Redis gián đoạn — đây là hành vi được chấp nhận có chủ đích (expected behavior), không phải lỗi, vì mục tiêu của fallback là ngăn traffic tăng vô hạn (bypass hoàn toàn), không phải giữ đúng ngưỡng toàn cục tuyệt đối trong lúc hạ tầng đang suy giảm.

## **6.2. Alert Cooldown Lock (notification-dispatcher) — đã sửa key + thêm TTL cho counter**

**Sửa (blocker bắt buộc):** Redis key v1.0 là alert:lock:{serviceId}:{ruleId} — thiếu environment. Vì cùng một serviceId có thể tồn tại ở nhiều environment khác nhau (payment-service/production và payment-service/staging), nếu ruleId không đảm bảo unique tuyệt đối theo environment thì có nguy cơ suppress nhầm cảnh báo giữa hai environment khác nhau. Đổi key thành alert:cooldown:{serviceId}:{environment}:{ruleId}.

**Sửa (nên sửa trước code):** counter alert:counter:{serviceId}:{ruleId} dùng INCRBY nhưng không có TTL — nếu không giới hạn thời gian sống, key này tồn tại vĩnh viễn trong Redis, tích lũy vô hạn theo thời gian dù chỉ có ý nghĩa thống kê trong đúng một cửa sổ cooldown. Counter phải có TTL khớp với cooldown (300 giây) và việc tăng + đặt hạn phải atomic (dùng Lua) để tránh race condition giữa hai lệnh riêng lẻ.

```
Redis Key (đã sửa):
  alert:cooldown:{serviceId}:{environment}:{ruleId}   (TTL = 300s)
  alert:counter:{serviceId}:{environment}:{ruleId}    (TTL = 300s, đồng bộ với cooldown)

Execution Pattern:
  1. acquired = SET alert:cooldown:... "LOCKED" NX EX 300
  2. Nếu acquired == false:
       Lua script atomic:
         if EXISTS(cooldown_key):
             INCR counter_key
             EXPIRE counter_key 300
       → Stop (không gửi)
  3. Nếu acquired == true:
       → Gửi Slack/Telegram Webhook
         Success  → giữ nguyên cooldown key (không đổi so với v1.0)
         Failure  → DEL cooldown key ngay lập tức để giải phóng
                    (khớp SDD v1.1 mục 4.3 — không suppress oan khi
                    chưa từng gửi thành công)
```

# **7\. Elasticsearch Engineering (indexer-worker & core-app)**

## **7.1. Elasticsearch Document Model (LogDocument.java) — đã bỏ dynamic annotation**

**Sửa (blocker bắt buộc):** v1.0 dùng @Document(indexName = "logs-#{T(java.time.LocalDate).now()...}") và khi bulk index lại dùng LocalDate.now() — cả hai đều lấy NGÀY HIỆN TẠI của thời điểm xử lý (processing time), không phải thời điểm log thực sự phát sinh (event time). Ví dụ: log có timestamp 2026-09-01 23:59:59, nhưng do Kafka delay ~2 phút, Indexer xử lý lúc 2026-09-02 00:01:00 — hệ thống sẽ ghi nhầm vào index logs-2026.09.02 thay vì logs-2026.09.01. Lỗi này ảnh hưởng trực tiếp đến: tìm kiếm theo ngày, chính sách retention/ILM, điều tra sự cố (incident investigation), và các log đến trễ (late-arriving logs).

Sửa: bỏ annotation @Document(indexName=...) động — LogDocument chỉ còn đóng vai trò document mapping/model thuần túy. Trách nhiệm quyết định tên index được chuyển hẳn sang tầng Indexer (IndexNameResolver), tính từ event.timestamp() theo UTC, không phụ thuộc đồng hồ hệ thống tại thời điểm xử lý — đây là kiến trúc tách bạch rõ ràng hơn giữa "document là gì" và "document thuộc index nào".

```
// LogDocument.java — chỉ còn là model, KHÔNG còn @Document(indexName=...) động
public class LogDocument {
    @Id
    private String eventId;                 // Mapped to _id cho Idempotency

    @Field(type = FieldType.Date)
    private Instant timestamp;

    @Field(type = FieldType.Keyword)  private String serviceName;
    @Field(type = FieldType.Keyword)  private String environment;
    @Field(type = FieldType.Keyword)  private String level;
    @Field(type = FieldType.Text, analyzer = "standard") private String message;
    @Field(type = FieldType.Keyword)  private String traceId;
    @Field(type = FieldType.Keyword)  private String spanId;
    @Field(type = FieldType.Keyword)  private String host;
    @Field(type = FieldType.Integer)  private Integer statusCode;
    @Field(type = FieldType.Integer)  private Integer durationMs;
    @Field(type = FieldType.Flattened) private Map<String, Object> metadata;  // Chốt tại mục 7.3
}

// IndexNameResolver.java — thành phần mới, chốt tên index từ EVENT TIMESTAMP
public final class IndexNameResolver {
    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneOffset.UTC);

    public static String resolve(CanonicalLogEvent event) {
        return "logs-" + FORMATTER.format(event.timestamp());   // event-time, KHÔNG dùng LocalDate.now()
    }
}
```

## **7.2. Bulk Indexer Request Construction — đã cập nhật dùng IndexNameResolver**

```
List<BulkOperation> bulkOperations = new ArrayList<>();
for (CanonicalLogEvent event : batch) {
    String indexName = IndexNameResolver.resolve(event);   // event.timestamp(), không LocalDate.now()
    bulkOperations.add(BulkOperation.of(b -> b
        .create(c -> c
            .index(indexName)
            .id(event.eventId())
            .document(event)
        )
    ));
}
BulkResponse response = esClient.bulk(b -> b.operations(bulkOperations));
// Lưu ý: một batch Kafka có thể chứa các event thuộc nhiều index khác nhau
// (log gần ranh giới nửa đêm UTC) — Bulk API cho phép trộn nhiều index
// trong cùng một request, không cần tách batch theo ngày.
```

## **7.3. Metadata Mapping — đã chốt (trước đây bị thiếu, blocker)**

**Sửa (blocker bắt buộc):** CanonicalLogEvent có Map&lt;String,Object&gt; metadata nhưng LogDocument v1.0 không có field này — nghĩa là dữ liệu metadata bị lặng lẽ rơi mất khi index vào Elasticsearch, không có mapping tường minh cho nó.

Quyết định: dùng kiểu flattened cho metadata, KHÔNG để Elasticsearch dynamic mapping tự sinh field con theo từng key. Lý do: metadata là một object có key động, tùy theo từng service/request (ví dụ region, customerTier, và các field tùy biến khác) — nếu để dynamic mapping tự động sinh field cho từng key mới gặp, số lượng field trong mapping sẽ tăng không kiểm soát theo thời gian (mapping explosion), ảnh hưởng hiệu năng và có thể khiến cluster từ chối nhận thêm mapping mới. Kiểu flattened lập chỉ mục toàn bộ object như một khối duy nhất, vẫn truy vấn được theo key/value nhưng không tạo field mapping riêng cho từng key con.

```
"metadata": { "type": "flattened" }
// Truy vấn ví dụ: { "term": { "metadata.customerTier": "premium" } }
// vẫn hoạt động với kiểu flattened, không cần field mapping tường minh
// cho từng key con.
```

# **8\. API Contract Specifications**

## **8.1. Ingestion API**

POST /api/v1/telemetry/logs

| **Header**   | **Ví dụ**                                                                      |
| ------------ | ------------------------------------------------------------------------------ |
| Content-Type | application/json                                                               |
| X-API-Key    | &lt;service-api-key&gt; (đã đổi từ ví dụ dạng thật sang placeholder — mục 8.4) |

```
Response 202 Accepted:
{
  "code": "ACCEPTED",
  "message": "Log event received successfully",
  "eventId": "c73a8f12-0b1a-4d2a-89bc-992a0172e12a",
  "timestamp": "2026-09-01T18:33:53Z"
}

Response 429 Too Many Requests:
{
  "code": "RATE_LIMIT_EXCEEDED",
  "message": "Rate limit exceeded. Maximum 1000 requests/sec allowed.",
  "timestamp": "2026-09-01T18:33:53Z",
  "path": "/api/v1/telemetry/logs"
}
```

## **8.2. Log Search API — đã bổ sung search_after cho deep pagination**

**Sửa (blocker bắt buộc):** v1.0 chỉ dùng page/size/totalElements/totalPages kiểu SQL pagination truyền thống. Với Elasticsearch chứa 10 triệu+ bản ghi, deep pagination (ví dụ page=50000) rất tốn tài nguyên (Elasticsearch phải quét và bỏ qua toàn bộ kết quả phía trước). Chốt hai chế độ dùng cho hai mục đích khác nhau.

| **Chế độ**   | **Dùng khi nào**                                                 | **Tham số**                                                                |
| ------------ | ---------------------------------------------------------------- | -------------------------------------------------------------------------- |
| page + size  | UI cơ bản, duyệt các trang đầu, size mặc định ≤ 100              | ?service=...&level=ERROR&page=0&size=20                                    |
| search_after | Deep pagination, xuất dữ liệu lớn, cuộn vô hạn (infinite scroll) | ?service=...&search_after=&lt;sort-values-của-bản-ghi-cuối-trang-trước&gt; |

```
GET /api/v1/logs?service=payment-service&level=ERROR&query=timeout&page=0&size=20
Headers: Authorization: Bearer <jwt_token>

Response 200 OK:
{
  "code": "SUCCESS",
  "data": {
    "content": [
      {
        "eventId": "c73a8f12-0b1a-4d2a-89bc-992a0172e12a",
        "timestamp": "2026-09-01T18:33:50Z",
        "serviceName": "payment-service",
        "level": "ERROR",
        "message": "Connection timeout to Bank Gateway after 5000ms",
        "traceId": "tr-abc-99",
        "spanId": "sp-01"
      }
    ],
    "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
  }
}
```

## **8.3. Unified Error Response Format — đã bổ sung validation errors**

**Sửa (nên sửa trước code):** bổ sung cấu trúc riêng cho lỗi validation (fieldErrors), hữu ích cho frontend/dashboard hiển thị lỗi theo từng trường thay vì chỉ một message chung chung.

```
Lỗi chung (giữ nguyên từ v1.0):
{
  "code": "RESOURCE_NOT_FOUND",
  "message": "Service with ID 'billing-service' does not exist",
  "timestamp": "2026-09-01T18:33:53Z",
  "path": "/api/v1/services/billing-service",
  "traceId": "tr-err-8812"
}

Lỗi validation (bổ sung mới):
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "timestamp": "2026-09-01T18:33:53Z",
  "path": "/api/v1/telemetry/logs",
  "fieldErrors": [
    { "field": "message", "message": "must not be blank" },
    { "field": "timestamp", "message": "must not be null" }
  ]
}
```

## **8.4. Ghi chú tài liệu — che ví dụ API Key (đã sửa)**

**Sửa (nên sửa trước code):** v1.0 dùng một ví dụ API Key trông như thật (epl_live_abc123xyz890) trong tài liệu — nếu tài liệu này được public (ví dụ trên GitHub), dễ gây hiểu nhầm hoặc bị quét tự động như một secret thật. Toàn bộ ví dụ trong tài liệu đổi sang dạng placeholder rõ ràng: &lt;service-api-key&gt; hoặc epl_live_&lt;redacted&gt;.

# **9\. System Sequence Diagrams**

## **9.1. Ingestion Flow Sequence (đã cập nhật — service identity & eventId)**

```
Client App        Ingestion API           Redis            Kafka Broker
    │                    │                   │                    │
    │── POST /logs ─────►│                   │                    │
    │  (X-API-Key,       │── Authenticate ──►│                    │
    │   eventId?)         │  & Check Quota    │                    │
    │                    │◄─ Token OK ────────│                    │
    │                    │                                         │
    │                    │── Resolve AuthenticatedServiceIdentity  │
    │                    │   (serviceId, environment — KHÔNG lấy   │
    │                    │    từ body của client)                 │
    │                    │                                         │
    │                    │── Mask Sensitive Data (Regex + Structured)
    │                    │                                         │
    │                    │── eventId: dùng client eventId nếu có, │
    │                    │   nếu không thì generate UUID           │
    │                    │                                         │
    │                    │── kafkaTemplate.send("raw-logs") ──────►│
    │                    │                                         │
    │                    │◄─ Broker ACK (RecordMetadata) ──────────│
    │◄─ HTTP 202 Accepted│                   │                    │
    │  { eventId }        │                   │                    │
```

## **9.2. Indexer Execution Sequence (đã cập nhật — ACK ordering & retry)**

```
Kafka Broker       Indexer Worker        Elasticsearch      Retry/DLQ Topic
    │                    │                     │                    │
    │── Poll Batch ─────►│                     │                    │
    │  (1,000 events)    │                     │                    │
    │                    │── Bulk (create) ───►│                    │
    │                    │  _id = eventId,     │                    │
    │                    │  index = resolve(event.timestamp)        │
    │                    │◄─ Bulk Response ────│                    │
    │                    │  (Items Result)     │                    │
    │                    │                                          │
    │                    │── Phân loại: success/409 OK,             │
    │                    │   429→retry, 400→dlq                     │
    │                    │──────────────────────────────────────────►│
    │                    │◄─── Producer ACK của retry/DLQ topic ─────│
    │                    │  (BẮT BUỘC có trước khi đi bước dưới)     │
    │                    │                     │                    │
    │                    │── Commit Sync Offset (raw-logs) ─►│      │
    │◄─ Commit ACK ──────│                     │                    │
```

# **10\. Concurrency & Transaction Management**

## **10.1. Concurrency Boundary Strategy**

- Virtual Threads (ingestion-service): spring.threads.virtual.enabled=true; không tạo Executor custom cho HTTP Requests (Spring Boot tự quản lý qua VirtualThreadExecutor); tránh synchronized block trong Core Pipeline, dùng ReentrantLock khi cần khóa trong lúc chờ I/O.
- Platform Threads (analytics-engine): Kafka Streams Engine chạy bằng Platform Threads truyền thống (num.stream.threads = 4) — không dùng Virtual Threads (khớp SDD v1.1 mục 2.1, Kafka Streams có execution model riêng).

### **Indexer Worker — chiến lược executor (đã sửa)**

**Sửa (blocker bắt buộc):** v1.0 dùng ThreadPoolTaskExecutor (coreSize=8, maxSize=16, queueCapacity=100) kèm CallerRunsPolicy cho Indexer Worker. Vấn đề: CallerRunsPolicy khi hàng đợi đầy sẽ buộc chính Kafka poll thread phải trực tiếp thực hiện tác vụ Elasticsearch indexing — nếu thao tác này chậm, Kafka poll thread bị block, có nguy cơ vi phạm max.poll.interval.ms (Kafka coi consumer là "đã chết" và kích hoạt rebalance ngoài ý muốn).

Khuyến nghị cho MVP: KHÔNG tự tạo executor pool riêng cho Indexer ở giai đoạn đầu. Thay vào đó, dùng Kafka listener concurrency (scale số lượng consumer instance/thread theo số partition) để tăng thông lượng xử lý — đơn giản hơn và không có rủi ro block poll thread. Nếu về sau thực sự cần một worker pool riêng (ví dụ để tách rời tốc độ poll và tốc độ xử lý), phải thiết kế rõ ràng theo mô hình: Kafka poll thread → bounded queue → worker pool, kèm chiến lược backpressure và hành vi poll tường minh — không áp dụng CallerRunsPolicy trực tiếp lên Kafka listener thread.

## **10.2. Transaction Boundaries**

| **Loại luồng**                     | **Ví dụ**                                                                 | **Quy tắc**                                                                                              |
| ---------------------------------- | ------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| Non-transactional, high-throughput | HTTP Ingestion → Kafka Publish; Kafka → Elasticsearch Bulk Index          | Không dùng @Transactional — tránh giữ DB Connection trong lúc xử lý Network I/O sang Kafka/Elasticsearch |
| DB local transaction               | Alert Consumer → PostgreSQL alerts; Service Registry → API Key Revocation | Dùng @Transactional của Spring Data JPA cho đúng một local transaction PostgreSQL                        |

**Sửa (blocker bắt buộc):** v1.0 mô tả "@Transactional đảm bảo việc đọc Alert Event và ghi PostgreSQL là Atomic" — cách nói này không chính xác: Kafka offset và PostgreSQL transaction không tự nhiên nằm trong cùng một distributed transaction. @Transactional chỉ đảm bảo tính atomic của RIÊNG thao tác ghi PostgreSQL. Nếu DB commit thành công nhưng process crash TRƯỚC KHI Kafka offset được ACK, message sẽ được đọc lại (Kafka at-least-once), có nguy cơ ghi Alert hai lần nếu không có idempotency ở tầng ứng dụng.

Wording chính xác (thay cho "atomic"): AlertConsumer sử dụng một local PostgreSQL transaction để ghi Alert. Kafka offset chỉ được acknowledge SAU KHI transaction PostgreSQL commit thành công. Vì Kafka offset và PostgreSQL transaction không phải một distributed transaction duy nhất, việc ghi Alert BẮT BUỘC phải idempotent, dùng AlertEntity.id (alertId) làm business key duy nhất: nếu INSERT alertId gặp unique violation (đã tồn tại) → coi là "đã xử lý trước đó", vẫn tiến hành ACK Kafka bình thường, không coi là lỗi.

# **11\. Ngữ nghĩa Alert Rule Engine (bổ sung mới)**

Đây là các quyết định ngữ nghĩa (semantics) còn để ngỏ ở v1.0 — bắt buộc phải chốt rõ trước khi code vì ảnh hưởng trực tiếp đến logic tính toán trong Kafka Streams.

## **11.1. ERROR_SPIKE — count hay rate?**

**Sửa (nên sửa trước code):** v1.0 có thresholdValue và windowSeconds nhưng không chốt rõ công thức: "100 errors / 60 giây" (đếm gộp trong cả cửa sổ) khác về bản chất với "100 errors / giây" (tốc độ tức thời). Hai cách hiểu cho ra ngưỡng cảnh báo hoàn toàn khác nhau.

Chốt ngữ nghĩa: ERROR_SPIKE = count(ERROR events) >= thresholdValue trong phạm vi một Tumbling Window có độ dài windowSeconds (đếm gộp trong cả cửa sổ, KHÔNG phải tốc độ tức thời theo giây).

## **11.2. Event-time vs Processing-time & Grace Period**

**Sửa (nên sửa trước code):** Kafka Streams có thể windowing theo event-time (dựa trên timestamp trong sự kiện) hoặc processing-time (dựa trên thời điểm xử lý thực tế) — hai lựa chọn cho kết quả khác nhau đáng kể khi có log đến trễ (late-arriving events), và v1.0 chưa chốt rõ lựa chọn nào.

Chốt: dùng event-time semantics (nhất quán với việc index Elasticsearch cũng dùng event.timestamp() ở mục 7.1, không dùng thời điểm xử lý). Kèm theo một grace period để dung nạp log đến trễ trong giới hạn hợp lý — ví dụ window = 60 giây, grace = 10 giây: một log có event timestamp 10:00:59 nhưng thực tế đến hệ thống lúc 10:01:08 (trễ 9 giây) vẫn được tính vào đúng window bắt đầu lúc 10:00:00, vì còn trong phạm vi grace period.

## **11.3. Alert Persistence — chống trùng ở business level**

**Sửa (nên sửa trước code):** ngoài alertId (đã có, mục 10.2), nếu cùng một Kafka Streams event bị emit lại (duplicate emit) cho đúng một window/rule/service, hệ thống nên có khả năng nhận diện đây là cùng một alert nghiệp vụ, không chỉ dựa vào alertId (vốn được sinh mới mỗi lần emit nếu không cẩn thận).

Business key tham khảo để chống trùng: (ruleId, serviceId, environment, windowStart) — khớp các trường đã bổ sung ở AlertEntity (mục 2.1). Quyết định có ràng buộc unique constraint chặt trên tổ hợp này hay không phụ thuộc vào việc nghiệp vụ có chấp nhận multiple occurrences hợp lệ trong cùng một window hay không — điểm mấu chốt là quyết định này PHẢI được ghi rõ trong tài liệu, không để ngỏ cho lúc code tự suy đoán.

# **12\. Verification & Test Strategy Implementation**

## **12.1. Integration Testing với Testcontainers — đã mở rộng theo từng component**

**Sửa (blocker bắt buộc):** v1.0 chỉ minh họa KafkaContainer + Redis cho một test case Ingestion, nhưng dự án còn cần PostgreSQL và Elasticsearch cho các thành phần khác. Chốt test suite theo đúng phạm vi hạ tầng mà từng component thực sự phụ thuộc — không bắt mọi test đều khởi tạo toàn bộ 4 container (chậm không cần thiết).

| **Test Suite**      | **Container cần thiết** |
| ------------------- | ----------------------- |
| Ingestion Test      | Kafka + Redis           |
| Indexer Test        | Kafka + Elasticsearch   |
| Alert Consumer Test | Kafka + PostgreSQL      |
| Notification Test   | Kafka + Redis           |

```
@SpringBootTest
@Testcontainers
class IngestionPipelineIntegrationTest {

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.0-alpine"))
        .withExposedPorts(6379);

    @Test
    void testIngestionFlow_ShouldPublishToKafkaSuccessfully() {
        // Given: Valid API Key & Log Request (không có serviceName trong payload — mục 4.1)
        // When: Call POST /api/v1/telemetry/logs
        // Then: Assert HTTP 202 Accepted & Kafka Topic chứa CanonicalLogEvent
        //       với serviceName khớp AuthenticatedServiceIdentity, không phải giá trị từ request
    }

    @Test
    void testIngestionFlow_RetryWithSameEventId_ShouldBeIdempotent() {
        // Given: cùng một eventId được gửi 2 lần (giả lập client retry)
        // When: Cả hai request được xử lý tới Elasticsearch
        // Then: Chỉ có đúng 1 document trong Elasticsearch với _id = eventId
        //       (lần 2 nhận 409 Conflict, coi là idempotent success)
    }
}
```

## **12.2. Contract Test cho Canonical Event (bổ sung mới)**

**Sửa (nên sửa trước code):** bổ sung: đây là hệ thống event-driven nên tính tương thích schema giữa producer (ingestion-service) và các consumer (indexer-worker, analytics-engine) cực kỳ quan trọng — nếu producer serialize một trường theo kiểu khác với kiểu mà consumer kỳ vọng deserialize (ví dụ durationMs là số nhưng bị hiểu nhầm thành chuỗi), toàn bộ pipeline downstream sẽ lỗi âm thầm hoặc crash.

```
CanonicalLogEventContractTest — đảm bảo chu trình:
    Java object (CanonicalLogEvent)
        → serialize sang JSON
        → deserialize lại
        → so sánh: kết quả phải là một object có cùng ngữ nghĩa
                    (semantically equal) với object gốc

Đồng thời: trường schemaVersion (mục 3.2) cho phép tiến hóa Canonical
Event Schema an toàn về sau (v1 -> v2) — consumer có thể kiểm tra
schemaVersion để quyết định cách deserialize phù hợp, thay vì giả định
mọi message luôn theo đúng một cấu trúc cố định.
```

## **12.3. Verification Checklist trước khi code (đã cập nhật)**

- Project Structure: 7 module Maven đã định nghĩa đầy đủ, có bổ sung RetryWorker trong indexer-worker (mục 1, mục 5.3).
- Entities: JPA Entities đã chốt Data Type, Nullability, Foreign Keys, và nhất quán FK mapping (mục 2 — đã sửa AlertRuleEntity).
- Canonical Model: CanonicalLogEvent đã chốt Immutable, có eventId tùy chọn từ client và schemaVersion (mục 3.2 — đã sửa producer idempotency).
- Service Identity: serviceName/environment lấy từ AuthenticatedServiceIdentity, không lấy từ client payload (mục 4.1 — đã sửa security boundary).
- Bulk Strategy: Elasticsearch Indexer có mô hình xử lý ItemResult độc lập, ACK theo đúng thứ tự retry/DLQ trước rồi mới ACK gốc (mục 5.2 — đã sửa thứ tự ACK).
- Retry Worker: đã chốt rõ luồng RetryWorker + RetryPolicy, không còn để ngỏ (mục 5.3 — đã bổ sung).
- Elasticsearch Index: tên index tính từ event.timestamp() qua IndexNameResolver, không dùng LocalDate.now() (mục 7.1 — đã sửa).
- Elasticsearch Metadata: đã chốt mapping kiểu flattened (mục 7.3 — đã bổ sung).
- Alert Persistence: đã sửa wording từ "atomic" sang "idempotent consumer + local transaction", có business key chống trùng (mục 10.2, 11.3 — đã sửa).
- Concurrency: đã xác định chính xác phạm vi Virtual Threads/Platform Threads, và thay CallerRunsPolicy bằng Kafka listener concurrency cho Indexer MVP (mục 10.1 — đã sửa).
- Rate Limit: Lua Script hỗ trợ millisecond precision kèm Local Fallback đã xác định rõ scope (100 req/s/instance, không đảm bảo global limit) (mục 6.1 — đã sửa).
- Test Matrix: Integration Test đã chia theo đúng hạ tầng cần thiết từng component, có bổ sung Contract Test cho Canonical Event (mục 12 — đã bổ sung).

**TRẠNG THÁI HỆ THỐNG: 🟢 READY TO CODE — bắt đầu Phase 1: Infrastructure (Docker Compose: PostgreSQL, Kafka KRaft, Redis, Elasticsearch) + Maven Multi-Module + platform-common, sau đó triển khai theo từng vertical slice thay vì viết toàn bộ module cùng lúc.**