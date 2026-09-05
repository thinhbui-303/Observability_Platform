**TÀI LIỆU THIẾT KẾ HỆ THỐNG**

**(SYSTEM DESIGN DOCUMENT – SDD)**

_Enterprise Log Aggregation & Real-time Observability Platform_

Derived directly from SRS v2.1

**Phiên bản SDD v1.1 — Ready-to-Implement Architecture**

_Đã áp dụng toàn bộ 14 điểm blocker bắt buộc và 5 điểm nên có từ vòng review Senior Backend / System Designer_

# **Lịch sử thay đổi tài liệu**

| **Phiên bản** | **Trạng thái**     | **Mô tả**                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | **Đánh giá tổng thể**        |
| ------------- | ------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------- |
| v1.0          | Draft              | Bản thiết kế sơ thảo dựa trên SRS v2.1: kiến trúc tổng thể, Ingestion, Kafka, Redis, Elasticsearch, ERD, Failure Model, Roadmap.                                                                                                                                                                                                                                                                                                                                                                                     | 8.2/10 — chưa nên code ngay  |
| v1.1          | Ready to Implement | Sửa toàn bộ 13 điểm 🔴 bắt buộc (Kafka semantics, Retry/DLQ, Redis atomicity & fallback, Elasticsearch idempotency & bulk handling, Alert persistence, WebSocket ownership, DB normalization, KRaft consistency) và 5 điểm 🟡 nên có (API key rotation, structured masking, platform self-monitoring, ILM local/production distinction, explicit ordering guarantee). Không mở rộng thêm các hạng mục 🟢 (multi-region, Kubernetes, MirrorMaker, cross-cluster replication, distributed tracing của chính platform). | Mục tiêu: Ready to Implement |

_Ghi chú: SRS v2.1 không thay đổi. Toàn bộ chỉnh sửa ở phiên bản này thuộc tầng thiết kế kỹ thuật (System Design), không phát sinh yêu cầu nghiệp vụ mới._

# **1\. Tổng quan kiến trúc (High-Level Architecture)**

## **1.1. Ranh giới Modularity & Triển khai**

Giữ nguyên định hướng v1.0 (đánh giá tốt, không thay đổi): hệ thống chia thành 2 nhóm khối triển khai để cân bằng giữa độ phức tạp hạ tầng và khả năng mở rộng.

- Observability Core App (Modular Monolith): Auth, Service Management, Alert Management, Log Query, Audit Log, WebSocket Dashboard Server — nghiệp vụ điều hướng/quản trị/hiển thị, tải thấp hơn, không cần scale độc lập theo throughput log.
- High-Throughput Streaming Workers (Independent Deployable Services): Ingestion Service, Indexer Worker, Kafka Streams Analytics Engine, Alert Consumer, Notification Dispatcher — các dịch vụ I/O-bound/throughput cao, scale độc lập theo chiều ngang.

**Sửa so với v1.0:** bổ sung Alert Consumer như một streaming worker độc lập (tách khỏi Notification Dispatcher — xem mục 6), phản ánh đúng kiến trúc chốt tại mục 1.2.

## **1.2. Sơ đồ kiến trúc tổng thể (đã chốt)**

```
Microservices
     │ API Key
     ▼
┌─────────────────┐
│ Ingestion        │  (Java 21 Virtual Threads — xem mục 2.1)
│ Service          │
└────────┬─────────┘
         │ ACK (chỉ 202 sau khi Kafka broker ACK)
         ▼
┌─────────────────────────────┐
│ Kafka: raw-logs              │
│ 12 partitions, key=serviceId │
└──────────┬───────────────────┘
           │
     ┌─────┴──────────┐
     ▼                ▼
┌──────────┐   ┌───────────────┐
│ Indexer  │   │ Kafka Streams  │
│ Worker   │   │ Analytics      │
└────┬─────┘   └───────┬────────┘
     ▼                 ▼
┌──────────────┐  Kafka: system-alerts
│Elasticsearch │       │
└──────────────┘  ┌────┴─────┐
                   ▼          ▼
            Alert Consumer  Notification
                   │         Dispatcher
                   ▼            │
              PostgreSQL   ┌────┴────┐
                   │       ▼         ▼
                   ▼     Slack   Telegram
              Core App
                   │
                   ▼
              WebSocket Dashboard

Redis (song song, không nằm trên luồng chính)
  ├── Rate Limiting (Token Bucket)
  ├── Alert Cooldown/Deduplication
  └── Service State / Dashboard Cache
```

**Sửa so với v1.0:** đây là kiến trúc đã chốt sau khi giải quyết Blocker #8 và #9 — bổ sung Alert Consumer ghi Alert xuống PostgreSQL, và WebSocket không còn đi qua Notification Dispatcher mà do Core App tự đọc/nhận sự kiện alert để đẩy lên Dashboard (chi tiết mục 6).

# **2\. Thiết kế chi tiết Ingestion Pipeline**

## **2.1. Java 21 Virtual Threads Strategy (đã hiệu chỉnh phạm vi)**

**Sửa so với v1.0:** mô tả v1.0 dùng lý lẽ "Virtual Thread unmount khi KafkaProducer.send() chờ ACK" để suy ra Virtual Thread làm Kafka producer tối ưu hơn — đây là lập luận không chính xác. Kafka Producer client vốn đã bất đồng bộ (asynchronous) theo thiết kế; việc gọi .get() trên Future mới thực sự block thread gọi. Virtual Thread không khiến Kafka Producer trở nên "magically scalable" hơn.

Mô tả lại chính xác: Virtual Threads được dùng cho concurrency theo mô hình request-per-task tại tầng HTTP của Ingestion Service. Kafka Producer vẫn hoạt động bất đồng bộ như bản chất của nó. Request lifecycle chỉ chờ (synchronously wait) kết quả gửi Kafka khi hợp đồng ingestion (SRS BR — chỉ trả 202 sau khi có ACK từ broker) yêu cầu điều đó. Virtual Thread chủ yếu làm giảm chi phí giữ chỗ (giữ OS thread) trong khoảng thời gian chờ downstream không thể tránh khỏi, chứ không phải "tăng tốc" bản thân Kafka Producer.

Phạm vi áp dụng Virtual Threads được thu hẹp rõ ràng, không dùng tràn lan:

- HTTP request handling (Tomcat virtual threads, server.tomcat.threads.max không còn là nút thắt).
- Các dependency validation có tính chất blocking (nếu có).
- Việc chờ đồng bộ kết quả gửi Kafka khi ingestion contract yêu cầu ACK trước khi trả response.
- Tránh Thread Pinning: dùng ReentrantLock thay vì synchronized block trong code lõi khi có I/O chờ.

**Sửa so với v1.0:** Kafka Streams Analytics Engine KHÔNG dùng Virtual Threads — Kafka Streams có execution/threading model riêng (StreamThread), không hưởng lợi và không nên áp Virtual Thread lên tầng này.

## **2.2. Ingestion Pipeline chi tiết (đã chốt)**

```
HTTP Request (POST /api/v1/telemetry/logs, X-API-Key)
     │
     ▼
Virtual Thread (request-per-task)
     │
     ▼
API Key Authentication  (so khớp apiKeyHash — xem mục 7.5)
     │
     ▼
Rate Limiter (Redis Token Bucket — mục 4.1)
     ├── denied ──► 429 Too Many Requests
     ▼
Schema Validation (Canonical Event Schema)
     ├── invalid ──► 400 Bad Request
     ▼
Data Masking (Structured field masking + Regex fallback — mục 8)
     ▼
Canonical Event (gán eventId = immutable UUID)
     ▼
Kafka Producer.send("raw-logs", serviceId, event)
     ├── failure ──► 503 Service Unavailable
     ▼
Kafka ACK (broker xác nhận)
     ▼
202 Accepted
```

## **2.3. Cơ chế Chống ngập (Backpressure)**

Giữ nguyên định hướng v1.0: khi Elasticsearch chậm/sập, Indexer Worker bị chậm theo, dữ liệu dồn lại trong Kafka raw-logs. Ingestion Service không gọi trực tiếp Elasticsearch nên vẫn tiếp tục nhận log ở tốc độ bình thường cho đến khi dung lượng lưu trữ Kafka đạt ngưỡng retention cấu hình.

# **3\. Thiết kế Kafka (Kafka Pipeline & Consumer Architecture)**

## **3.1. Partitioning Strategy (đã hiệu chỉnh — không tối ưu sớm)**

Topic raw-logs: 12 partitions (mặc định). Partition key = serviceId.

**Sửa so với v1.0:** v1.0 đề xuất composite key serviceId + "\_" + (hash(traceId) % 3) để xử lý hot partition ngay từ đầu. Vấn đề: cách này phá vỡ tính thứ tự tương đối (ordering) của log cùng một service — log A/B/C của cùng service có thể rơi vào các partition khác nhau và mất thứ tự A→B→C. Quyết định lại:

- Version 1 (áp dụng ngay): giữ nguyên partitionKey = serviceId, KHÔNG triển khai hot-partition spreading ngay từ đầu (không tối ưu sớm/premature optimization).
- Version 2 (chỉ khi có bằng chứng benchmark cho thấy hot partition thực sự là bottleneck): mới cân nhắc chuyển sang serviceId + bucket, và khi đó phải chấp nhận và ghi rõ đánh đổi về ordering.

**Cam kết về thứ tự (explicit ordering guarantee):** log của cùng một serviceId có thứ tự tương đối được đảm bảo trong phạm vi một partition (Version 1). Đây là ordering best-effort ở mức service, không phải ordering toàn cục. Nếu về sau áp dụng chiến lược bucket (Version 2), ordering giữa các bucket của cùng service không còn được đảm bảo — timestamp trong mỗi log event là nguồn xác định thứ tự thời gian có thẩm quyền (authoritative for temporal ordering), không phụ thuộc vào thứ tự ghi vật lý.

Trace Correlation trong môi trường partition theo service: do partition key là serviceId, log cùng traceId từ nhiều service khác nhau nằm ở các partition khác nhau — điều này chấp nhận được vì Trace Correlation không thực hiện ở tầng Kafka mà được đẩy xuống tầng Elasticsearch bằng truy vấn theo traceId, sắp xếp theo timestamp (khớp FR-TRC-01/02 trong SRS).

## **3.2. Consumer Groups & Commit Offset Strategy**

### **indexer-group (ghi Elasticsearch)**

Config: enable.auto.commit = false (manual commit).

**Sửa so với v1.0:** v1.0 mô tả "ES trả 200 OK → commitSync()" — chưa đủ, vì Elasticsearch Bulk API có thể trả HTTP 200 ở mức response tổng nhưng vẫn chứa item lỗi ở mức từng document (item-level failure). HTTP 200 không đồng nghĩa toàn bộ item trong batch đều thành công. Luồng xử lý được thiết kế lại (chi tiết đầy đủ tại mục 5.3):

```
Kafka Batch (ví dụ 1,000 records)
     ▼
Elasticsearch Bulk Request
     ▼
Phân loại kết quả theo từng item:
  ├── success items
  └── failed items
        ├── retryable   (ví dụ 429, timeout tạm thời)
        └── non-retryable (ví dụ mapping error, dữ liệu sai schema)
     ▼
Chỉ gọi consumer.commitSync() SAU KHI mọi record trong batch
đã được xử lý xong theo policy:
  success        → coi là done
  retryable      → đẩy sang logs.retry.1s (mục 3.3)
  non-retryable  → đẩy thẳng vào logs.dlq kèm metadata lỗi
```

Bảo vệ at-least-once: nếu Indexer Worker crash giữa chừng (trước khi commit), offset chưa được commit — consumer mới lên sẽ đọc lại batch đó. Idempotency ở tầng Elasticsearch (mục 5.2) đảm bảo việc xử lý lại không tạo document trùng có nội dung sai lệch.

### **analytics-group (Kafka Streams Engine)**

- Config: state store trên RocksDB local.
- Windowing: Tumbling Window 60 giây.
- Output: phát AlertEvent vào topic system-alerts.

## **3.3. Retry & Dead Letter Queue (DLQ) Architecture (đã thiết kế lại)**

**Sửa so với v1.0:** v1.0 dùng một topic logs.retry duy nhất mà Retry Consumer đọc và thử lại ngay lập tức — Kafka không phải delay queue tự nhiên, nên retry ngay lập tức không tạo đủ khoảng nghỉ (backoff) trước khi thử lại, dễ gây retry storm khi lỗi có tính hệ thống (ví dụ Elasticsearch đang quá tải). Thiết kế lại theo chuỗi retry topic có độ trễ tăng dần — dễ giải thích và dễ quan sát (observable) hơn so với dùng scheduler riêng trong worker.

```
raw-logs
    │
    ▼
Indexer Worker
    │
    ├── success ──────────────────────► Elasticsearch
    │
    └── retryable failure
             │
             ▼
        logs.retry.1s   (đợi ~1s trước khi xử lý lại)
             │ vẫn lỗi
             ▼
        logs.retry.5s   (đợi ~5s)
             │ vẫn lỗi
             ▼
        logs.retry.30s  (đợi ~30s)
             │ vẫn lỗi (đã vượt retryCount tối đa)
             ▼
        logs.dlq ──► Admin UI Dashboard (Retry thủ công / Discard)
```

Metadata bắt buộc đính kèm trên mỗi message tại logs.retry.\*/logs.dlq (dưới dạng Kafka message header) để phục vụ debug và audit:

- eventId
- originalTopic
- originalPartition
- originalOffset
- retryCount
- errorType
- errorMessage
- firstFailedAt

Lưu ý về ordering: log đi qua đường retry có thể được index trễ hơn log đi qua đường thành công trực tiếp, dẫn đến thứ tự ghi vào Elasticsearch không khớp hoàn toàn thứ tự phát sinh gốc giữa hai đường xử lý. Điều này chấp nhận được cho một hệ thống log vì timestamp trong sự kiện là nguồn xác định thứ tự thời gian có thẩm quyền (đã nêu tại mục 3.1), không phải thứ tự ghi vật lý.

# **4\. Thiết kế Redis (Ephemeral State & Dynamism)**

## **4.1. Token Bucket Rate Limiting (đã sửa công thức + độ chính xác)**

Redis Key Pattern: ratelimit:service:&lt;serviceId&gt;. Thuật toán triển khai bằng Redis Lua Script để đảm bảo tính atomic.

**Sửa so với v1.0:** Lua script v1.0 có lỗi cú pháp (thiếu phép nhân: "tokens + delta limit" thay vì "tokens + delta \* limit") và dùng timestamp theo giây (now tính bằng giây) trong khi limit lên tới hàng nghìn request/giây — độ phân giải theo giây quá thô: nếu 100 request đến trong cùng một giây, delta luôn bằng 0 giữa các lần gọi liên tiếp, khiến token gần như không được nạp lại kịp trong cửa sổ đó.

```
-- Redis Lua Script (atomic) — độ phân giải millisecond
local key       = KEYS[1]
local capacity  = tonumber(ARGV[1])  -- burst, vd 1200
local refillRate = tonumber(ARGV[2]) -- tokens nạp / giây, vd 1000
local now_ms    = tonumber(ARGV[3])  -- timestamp hiện tại tính bằng ms

local last_updated = tonumber(redis.call('HGET', key, 'last_updated') or now_ms)
local tokens        = tonumber(redis.call('HGET', key, 'tokens') or capacity)

-- Nạp lại token theo thời gian trôi qua (đơn vị ms), quy đổi ra refillRate/1000ms
local delta_ms = math.max(0, now_ms - last_updated)
local refilled = (delta_ms / 1000.0) * refillRate
tokens = math.min(capacity, tokens + refilled)

if tokens >= 1 then
  tokens = tokens - 1
  redis.call('HSET', key, 'tokens', tokens, 'last_updated', now_ms)
  redis.call('EXPIRE', key, 60)
  return 1 -- ALLOWED
else
  redis.call('HSET', key, 'tokens', tokens, 'last_updated', now_ms)
  redis.call('EXPIRE', key, 60)
  return 0 -- DENIED (HTTP 429)
end
```

Tham số ví dụ: capacity (burst) = 1200, refillRate = 1000 token/giây — client gửi timestamp hiện tại theo millisecond (System.currentTimeMillis()) thay vì giây.

## **4.2. Chiến lược khi Redis gặp sự cố (Redis Failure Strategy) — bổ sung mới**

**Sửa so với v1.0:** v1.0 quy định "Redis crash → tự động bypass Rate Limiter và Cooldown Lock" để hệ thống không bị nghẽn. Đây là một failure amplification nguy hiểm: khi Redis chết, rate limiting tắt hoàn toàn → traffic không còn bị giới hạn → có thể làm quá tải Kafka/Elasticsearch → toàn hệ thống mất ổn định thay vì chỉ một phần bị ảnh hưởng.

Quyết định: Local fallback with conservative limit — thay vì bypass hoàn toàn hoặc fail-closed hoàn toàn (đánh đổi availability quá nhiều), mỗi instance Ingestion Service duy trì một Token Bucket cục bộ (in-memory) với ngưỡng thận trọng, dùng khi không kết nối được Redis:

| **Trạng thái Redis**                         | **Rate limit áp dụng**                                                                                                                                                        |
| -------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Bình thường                                  | 1000 request/giây theo cấu hình trong Redis (toàn cục, chia sẻ giữa các instance)                                                                                             |
| Redis unavailable (RedisConnectionException) | Fallback: 100 request/giây/instance (local, không chia sẻ trạng thái) — thận trọng hơn nhiều so với bình thường, đủ để tránh sập hệ thống downstream trong lúc Redis phục hồi |

Alert Cooldown Lock khi Redis mất kết nối: hệ thống chuyển sang Degraded Mode có kiểm soát — không suppress được cảnh báo trùng lặp trong thời gian này (chấp nhận khả năng gửi trùng alert tạm thời), thay vì bypass rate limiter (rủi ro về ổn định hệ thống hạ tầng, mức độ nghiêm trọng cao hơn so với việc nhận trùng một vài thông báo).

## **4.3. Alert Deduplication / Suppression Cooldown Engine (đã sửa race condition)**

Redis Key Pattern: alert:lock:&lt;serviceId&gt;:&lt;ruleId&gt;.

**Sửa so với v1.0:** v1.0: SET NX EX 300 thành công → tiến hành gửi Slack/Telegram. Nếu bước gửi thông báo thất bại (ví dụ Slack API lỗi), lock 5 phút vẫn tồn tại → các lần phát hiện lỗi tiếp theo trong 5 phút đó bị suppress dù chưa có thông báo nào thực sự được gửi thành công. Đây là một lỗi business logic (race condition giữa việc chiếm lock và việc gửi thành công).

```
1. SET alert:lock:<serviceId>:<ruleId> "LOCKED" NX EX 300
2. Nếu trả về NIL (lock đã tồn tại) → bỏ qua gửi, chỉ tăng
   Counter tại alert:counter:<serviceId>:<ruleId>
3. Nếu trả về OK (chiếm được lock) → tiến hành gửi Slack/Telegram
     ├── Gửi thành công → giữ nguyên cooldown 300 giây
     └── Gửi thất bại   → DEL alert:lock:<serviceId>:<ruleId>
                           (giải phóng lock ngay để lần phát hiện
                            kế tiếp có cơ hội gửi lại, thay vì bị
                            khóa suppress oan trong 5 phút)
```

Đây là phương án đơn giản, phù hợp quy mô dự án (so với việc dựng một state machine đầy đủ NEW → CLAIMED → SENDING → SENT), vẫn giải quyết đúng gốc rễ vấn đề: không suppress cảnh báo khi chưa từng gửi thành công.

# **5\. Thiết kế Elasticsearch (Search & Storage Engine)**

## **5.1. Index Template Mapping Specification (đã sửa index pattern)**

**Sửa so với v1.0:** v1.0 khai báo index_patterns: \["logs-"\] — pattern này không khớp được với index thực tế dạng logs-2026.09.01 (thiếu ký tự đại diện). Đây là lỗi sẽ gây khó chịu thực sự lúc code (Index Template không bao giờ được áp dụng). Sửa thành logs-\*.

```
PUT _index_template/logs-template
{
  "index_patterns": ["logs-*"],
  "template": {
    "settings": {
      "index.refresh_interval": "5s",
      "number_of_shards": 3,
      "number_of_replicas": 1
    },
    "mappings": {
      "properties": {
        "eventId":     { "type": "keyword" },
        "timestamp":   { "type": "date" },
        "serviceName": { "type": "keyword" },
        "environment": { "type": "keyword" },
        "level":       { "type": "keyword" },
        "message":     { "type": "text", "analyzer": "standard" },
        "traceId":     { "type": "keyword" },
        "spanId":      { "type": "keyword" },
        "host":        { "type": "keyword" },
        "statusCode":  { "type": "integer" },
        "durationMs":  { "type": "integer" }
      }
    }
  }
}
```

refresh_interval được tăng lên 5s (thay vì mặc định 1s) để tối ưu throughput ghi theo lô (batch), đánh đổi với độ trễ hiển thị dữ liệu mới trong tìm kiếm gần-thực (near real-time thay vì real-time tuyệt đối) — chấp nhận được với use case log search.

## **5.2. Idempotent Indexing Mechanism (đã sửa semantic)**

**Sửa so với v1.0:** v1.0 dùng thao tác index trong Bulk API — khi trùng \_id, Elasticsearch sẽ GHI ĐÈ (overwrite) document, không phải "bỏ qua bản ghi trùng" như mô tả. Câu "triệt tiêu hoàn toàn lỗi trùng lặp data" quá mạnh so với thực tế: nếu cùng eventId nhưng payload khác (về lý thuyết là không nên xảy ra với UUID nhưng vẫn cần xử lý an toàn), ghi đè âm thầm là rủi ro. Sửa sang dùng thao tác create.

```
POST /_bulk
{ "create": { "_index": "logs-2026.09.01", "_id": "<eventId>" } }
{ "timestamp": "...", "serviceName": "payment-service", ... }
```

Semantic rõ ràng hơn với create:

| **Trường hợp**                                                               | **Kết quả Elasticsearch** | **Cách xử lý ở Indexer Worker**                                                                |
| ---------------------------------------------------------------------------- | ------------------------- | ---------------------------------------------------------------------------------------------- |
| Lần đầu ghi eventId                                                          | 201 Created               | Coi là thành công                                                                              |
| Ghi lại eventId đã tồn tại (Kafka gửi lại do retry/rebalance, at-least-once) | 409 Conflict              | Coi là "already indexed = idempotent success" — KHÔNG phải lỗi, không retry, không đẩy vào DLQ |

Cách tiếp cận này chắc chắn hơn cho một canonical event có eventId bất biến (immutable UUID) theo đúng nguyên tắc thiết kế trong SRS (mục 7.1).

## **5.3. Bulk Indexing & Item-level Failure Handling (bổ sung mới)**

**Sửa so với v1.0:** v1.0 mô tả "ES 200 OK → commitSync()" là chưa đủ (xem cảnh báo tại mục 3.2). Elasticsearch Bulk API trả HTTP 200 ở mức response bao ngoài ngay cả khi một số item bên trong thất bại (ví dụ item lỗi 429 do quá tải, hoặc lỗi mapping). Indexer Worker phải duyệt qua từng item trong response Bulk để phân loại chính xác.

```
Bulk Response
  items: [
    { create: { status: 201 } },              → success
    { create: { status: 409 } },              → idempotent success (mục 5.2)
    { create: { status: 429, error: {...} } },→ retryable   → logs.retry.1s
    { create: { status: 400, error: {...} } },→ non-retryable → logs.dlq
  ]

Offset của batch trong Kafka chỉ được commit SAU KHI toàn bộ item
trong batch đã được phân loại và định tuyến xong theo đúng policy.
```

## **5.4. Index Lifecycle Management (ILM) Policy**

- HOT (0–3 ngày): Primary Shards trên NVMe/SSD, cho phép ghi & tìm kiếm.
- WARM (4–30 ngày): chuyển read-only, shrink còn 1 shard, force merge segment.
- COLD (31–90 ngày): nén sâu, lưu trên lưu trữ chi phí thấp.
- DELETE (>90 ngày): tự động xóa index.

**Sửa so với v1.0:** các thao tác Shrink/Force Merge/chuyển node COLD phụ thuộc mạnh vào topology cluster Elasticsearch thực tế (nhiều node với node role hot/warm/cold riêng biệt). Nếu triển khai local bằng Elasticsearch single-node Docker, các pha này không thể hiện đúng ngữ nghĩa (semantics) của một cluster production thật. Ghi rõ ranh giới để tránh hiểu nhầm khi trình bày/interview:

**Local development:** dùng cấu hình Elasticsearch single-node; chính sách ILM vẫn được cấu hình và áp dụng để minh họa đúng thiết kế, nhưng không có sự tách vật lý giữa các node hot/warm/cold. **Production topology (định hướng, ngoài phạm vi triển khai local):** có thể dùng dedicated node roles (hot/warm/cold nodes) để ILM phát huy đúng giá trị tối ưu chi phí lưu trữ.

# **6\. Kiến trúc xử lý Alert (Alert Processing Architecture) — bổ sung mới**

Đây là một thành phần quan trọng còn thiếu ở v1.0: SDD chưa thể hiện rõ Alert (bảng alerts trong PostgreSQL, theo SRS mục 9) được tạo ra ở đâu trong luồng dữ liệu, và WebSocket Dashboard đang được gộp chung vào Notification Dispatcher — vốn không phải đúng trách nhiệm (concern) của nó.

## **6.1. Alert Consumer (Alert Persistence) — thành phần mới**

**Sửa so với v1.0:** bổ sung một consumer độc lập đọc topic system-alerts và chịu trách nhiệm ghi bản ghi Alert xuống PostgreSQL (bảng alerts, khớp SRS mục 9), tách biệt hoàn toàn khỏi việc gửi thông báo.

```
Kafka Streams Analytics Engine
        │
        ▼
   system-alerts
        │
   ┌────┴─────────────────┐
   ▼                       ▼
Alert Consumer        Notification Dispatcher
   │                       │
   ▼                  ┌────┴────┐
PostgreSQL            ▼         ▼
 (bảng alerts)      Slack   Telegram
```

## **6.2. WebSocket Dashboard — tách khỏi Notification Dispatcher**

**Sửa so với v1.0:** v1.0 để WebSocket là một nhánh gửi ra của Notification Dispatcher (song song với Slack/Telegram) — không hợp lý vì WebSocket Dashboard là concern của Core App (quản lý phiên kết nối người dùng, xác thực, hiển thị), không phải một "kênh thông báo bên ngoài" giống Slack/Telegram.

Quyết định: Core App tự đọc dữ liệu Alert cần hiển thị real-time (thông qua Alert Consumer ghi PostgreSQL, hoặc Core App tự subscribe topic system-alerts tùy giai đoạn triển khai LLD) và đẩy cập nhật qua WebSocket/STOMP tới Dashboard — không đi qua Notification Dispatcher.

Notification Dispatcher chỉ còn đúng một trách nhiệm: đọc system-alerts và gửi thông báo ra các kênh bên ngoài (Slack, Telegram) — kết hợp Redis Alert Lock/Cooldown (mục 4.3).

# **7\. Sơ đồ thực thể cơ sở dữ liệu (Database ERD – PostgreSQL)**

Dữ liệu vận hành (operational metadata) được lưu trên RDBMS tuân thủ tính toàn vẹn quan hệ, khớp ranh giới lưu trữ đã xác lập tại SRS v2.1 mục 9.1.

## **7.1. Users, Roles (giữ nguyên — đã đúng từ SRS v2.1)**

```
CREATE TABLE users (
  id BIGSERIAL PRIMARY KEY,
  username VARCHAR(50) UNIQUE NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE roles (
  id SERIAL PRIMARY KEY,
  name VARCHAR(30) UNIQUE NOT NULL, -- ADMIN, DEVOPS, DEVELOPER, VIEWER
  description VARCHAR(255)
);

CREATE TABLE user_roles (
  user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
  role_id INT REFERENCES roles(id) ON DELETE CASCADE,
  PRIMARY KEY (user_id, role_id)
);
```

## **7.2. Service Registry & API Key Rotation (đã bổ sung)**

**Bổ sung (nên có):** v1.0 lưu một api_key_hash duy nhất trực tiếp trong bảng services — không hỗ trợ xoay vòng khóa (key rotation) hay thu hồi (revoke) độc lập. Tách thành bảng service_api_keys riêng để hỗ trợ vòng đời API Key đầy đủ: tạo khóa mới → triển khai → thu hồi khóa cũ, mà không làm gián đoạn service đang chạy. Không bắt buộc cho MVP nhưng được đưa vào vì nâng chuẩn thiết kế lên system-level.

```
CREATE TABLE services (
  id VARCHAR(50) PRIMARY KEY,       -- e.g. 'payment-service'
  name VARCHAR(100) NOT NULL,
  team_owner VARCHAR(50) NOT NULL,
  environment VARCHAR(20) NOT NULL,
  status VARCHAR(20) DEFAULT 'ACTIVE',
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE service_api_keys (
  id BIGSERIAL PRIMARY KEY,
  service_id VARCHAR(50) REFERENCES services(id) ON DELETE CASCADE,
  key_prefix VARCHAR(12) NOT NULL,   -- hiển thị được, để nhận diện khóa
  key_hash VARCHAR(255) NOT NULL,    -- SHA-256 / BCrypt, không lưu plain-text
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP WITH TIME ZONE,
  revoked_at TIMESTAMP WITH TIME ZONE,
  last_used_at TIMESTAMP WITH TIME ZONE
);
```

Luồng xoay vòng khóa: tạo service_api_keys mới (expires_at có thể để trống) → cấu hình lại service producer dùng khóa mới → đặt revoked_at cho khóa cũ. Ingestion Service xác thực bằng cách so khớp hash với các bản ghi có revoked_at IS NULL và (expires_at IS NULL OR expires_at > now()).

## **7.3. Alert Rules, Alert Rule Channels (đã chuẩn hóa)**

**Sửa so với v1.0:** cột alert_rules.created_by trước đây là VARCHAR(50) lưu username — trong khi users.id là khóa chính kiểu BIGINT, nên created_by phải là khóa ngoại (FK) tham chiếu users(id) thay vì lưu chuỗi tên. Ngược lại, audit_logs.username vẫn giữ nguyên dạng snapshot chuỗi vì mục đích của audit log là lưu lại đúng tên hiển thị tại thời điểm xảy ra hành động (audit historical), kể cả khi user đó sau này đổi tên hoặc bị xóa.

**Sửa so với v1.0:** cột notification_channels VARCHAR(255) lưu CSV dạng "slack,websocket" — đây là kiểu dữ liệu CSV nhúng trong cơ sở dữ liệu quan hệ, khó truy vấn/mở rộng. Chuẩn hóa thành bảng con alert_rule_channels (quan hệ 1:N), thuận lợi khi mở rộng thêm kênh mới (WEBHOOK, WEBSOCKET…) về sau.

```
CREATE TABLE alert_rules (
  id BIGSERIAL PRIMARY KEY,
  rule_name VARCHAR(100) NOT NULL,
  service_id VARCHAR(50) REFERENCES services(id),
  environment VARCHAR(20) NOT NULL,
  condition_type VARCHAR(50) NOT NULL,   -- ERROR_SPIKE, PATTERN_MATCH
  threshold_value INT NOT NULL,
  window_seconds INT NOT NULL,
  severity VARCHAR(20) NOT NULL,          -- CRITICAL, HIGH, MEDIUM
  is_enabled BOOLEAN DEFAULT TRUE,
  created_by BIGINT REFERENCES users(id), -- FK, không còn lưu username dạng chuỗi
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE alert_rule_channels (
  id BIGSERIAL PRIMARY KEY,
  alert_rule_id BIGINT REFERENCES alert_rules(id) ON DELETE CASCADE,
  channel_type VARCHAR(20) NOT NULL,  -- SLACK, TELEGRAM, WEBHOOK, WEBSOCKET
  target VARCHAR(255),                -- ví dụ webhook URL / chat id
  enabled BOOLEAN DEFAULT TRUE
);
```

## **7.4. Alerts & Audit Logs (giữ nguyên)**

```
CREATE TABLE alerts (
  id VARCHAR(50) PRIMARY KEY,            -- UUID-v4
  rule_id BIGINT REFERENCES alert_rules(id),
  service_id VARCHAR(50) REFERENCES services(id),
  severity VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,           -- TRIGGERED, OPEN, ACKNOWLEDGED, RESOLVED
  triggered_at TIMESTAMP WITH TIME ZONE NOT NULL,
  acknowledged_at TIMESTAMP WITH TIME ZONE,
  resolved_at TIMESTAMP WITH TIME ZONE,
  occurrence_count INT DEFAULT 1
);

CREATE TABLE audit_logs (
  id BIGSERIAL PRIMARY KEY,
  username VARCHAR(50) NOT NULL,   -- snapshot tại thời điểm hành động (giữ nguyên có chủ đích)
  action VARCHAR(100) NOT NULL,
  resource_target VARCHAR(100) NOT NULL,
  ip_address VARCHAR(45) NOT NULL,
  result_status VARCHAR(20) NOT NULL,  -- SUCCESS, FAILED
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
```

# **8\. Chiến lược Data Masking (đã hiệu chỉnh)**

**Sửa so với v1.0:** v1.0 chỉ dựa vào Regex Pattern để nhận diện và che (mask) dữ liệu nhạy cảm (thẻ tín dụng, mật khẩu, access token). Regex xử lý tốt các trường có định dạng tường minh (ví dụ "password":"abc"), nhưng không đủ tin cậy với các dạng khó nhận diện nếu thiếu ngữ cảnh, ví dụ giá trị header Authorization dạng Bearer token hoặc một chuỗi bí mật tự do không theo định dạng chuẩn.

Chiến lược hai lớp: Structured field masking (ưu tiên) + Regex fallback.

- Structured field masking: với các trường đã biết trước theo cấu trúc của Canonical Event Schema (ví dụ headers.Authorization, metadata.token, các field được đánh dấu nhạy cảm), hệ thống che theo đúng tên trường (field-based), không phụ thuộc vào việc nhận diện định dạng nội dung.
- Regex fallback: áp dụng lên các trường tự do (đặc biệt là message) để bắt các mẫu định dạng phổ biến còn lại (số thẻ tín dụng, chuỗi dạng password=..., token=...).
- Thứ tự xử lý: mask theo structured field trước, sau đó mới chạy regex fallback trên phần nội dung còn lại, để giảm rủi ro bỏ sót so với chỉ dùng regex đơn thuần.

# **9\. Mô hình xử lý lỗi và khôi phục (Failure & Recovery Model)**

| **Loại sự cố**       | **Cơ chế phát hiện**                                                 | **Hành động xử lý (đã cập nhật)**                                                                                                                                                              | **Đảm bảo dữ liệu**                                                                |
| -------------------- | -------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| Elasticsearch Down   | Indexer Worker nhận ElasticsearchException hoặc connection timeout   | Pause consumer (consumer.pause()), ngừng đọc Kafka. Ingestion API vẫn nhận log, trả 202. Log dồn lại tại Kafka. Khi ES phục hồi → resume consumer.                                             | Zero log loss trong phạm vi retention của Kafka                                    |
| Kafka Broker Crash   | Ingestion Service nhận TimeoutException khi gọi kafkaTemplate.send() | Ingestion API trả 503 Service Unavailable cho client. Client thực hiện retry với max retry = 3, exponential backoff, jitter bật (chống retry storm khi Kafka phục hồi — xem ghi chú bên dưới). | Microservice Producer chịu trách nhiệm retry có kiểm soát                          |
| Indexer Worker Crash | Kafka Broker phát hiện mất heartbeat từ consumer instance            | Consumer Group rebalance; partition được gán cho instance khác, tiếp tục từ last committed offset.                                                                                             | At-least-once delivery (bản trùng bị loại bởi ES idempotency — mục 5.2)            |
| Redis Crash          | Spring Boot bắt RedisConnectionException                             | Local fallback với ngưỡng thận trọng (100 req/s/instance) thay vì bypass hoàn toàn — xem mục 4.2. Alert cooldown chuyển sang Degraded Mode có kiểm soát.                                       | Hệ thống chuyển Degraded Mode an toàn, không mất kiểm soát rate limiting hoàn toàn |

**Sửa so với v1.0:** hàng "Redis Crash" đã thay đổi từ "tự động bypass Rate Limiter" (rủi ro làm nghẽn Kafka/Elasticsearch do mất kiểm soát traffic — failure amplification) sang "local fallback thận trọng" (mục 4.2).

**Sửa so với v1.0:** bổ sung yêu cầu chống retry storm ở phía client: khi Kafka phục hồi sau sự cố, nếu hàng chục/hàng trăm service cùng retry đồng loạt không kiểm soát sẽ tạo ra một đợt tải bùng nổ làm Kafka quá tải ngay khi vừa hồi phục. Quy định rõ cho Microservice Producer: max retry = 3, backoff dạng exponential, có jitter (độ trễ ngẫu nhiên hóa) để trải đều thời điểm các client thử lại.

# **10\. Giám sát nội bộ nền tảng (Platform Self-Observability) — bổ sung mới**

**Bổ sung (nên có):** một Observability Platform mà chính bản thân nó lại không được thiết kế giám sát là một nghịch lý cần tránh (khớp SRS v2.1 mục 6.6/19). Bổ sung danh mục metric cụ thể theo từng thành phần để làm cơ sở cho Dashboard nội bộ và cảnh báo vận hành.

| **Thành phần**    | **Metric cần thu thập**                                                                      |
| ----------------- | -------------------------------------------------------------------------------------------- |
| Ingestion Service | request count, tỉ lệ 4xx, tỉ lệ 5xx, latency (P50/P95/P99), số request bị rate-limit từ chối |
| Kafka             | consumer lag theo consumer group, throughput theo topic, số lượng message trong logs.dlq     |
| Indexer Worker    | indexing rate, tỉ lệ indexing thất bại theo item, retry count, bulk request latency          |
| Elasticsearch     | search latency, indexing latency, disk usage theo index                                      |
| Redis             | command latency, tỉ lệ lỗi kết nối, tình trạng khả dụng (availability)                       |

# **11\. Kiến trúc triển khai (Deployment Architecture)**

**Sửa so với v1.0:** v1.0 vừa nêu dùng "Kafka (KRaft)" ở phần thiết kế, vừa liệt kê "Kafka, Zookeeper, Elasticsearch..." ở roadmap Docker Compose — hai mô tả mâu thuẫn nhau. Kafka chạy theo chế độ KRaft thì KHÔNG cần Zookeeper. Chốt dùng KRaft (Kafka hiện đại, đơn giản hóa hạ tầng local) và loại bỏ Zookeeper khỏi toàn bộ tài liệu.

```
Docker Compose (local development)
├── postgres
├── redis
├── kafka          (chế độ KRaft — không có zookeeper)
├── elasticsearch  (single-node — xem ghi chú mục 5.4)
│
├── ingestion-service
├── indexer-worker
├── analytics-engine
├── alert-consumer
├── notification-dispatcher
└── core-app
```

# **12\. Cấu trúc dự án (Project Structure) — đã cập nhật**

**Sửa so với v1.0:** bổ sung 2 module độc lập alert-consumer và notification-dispatcher (tách theo đúng kiến trúc mục 6 — persistence và notification là hai trách nhiệm khác nhau, không nên gộp chung), đổi tên platform-core → platform-common cho rõ nghĩa (thư viện dùng chung, không phải một service).

```
observability-platform/
├── docker-compose.yml        (postgres, redis, kafka-kraft, elasticsearch)
├── pom.xml / build.gradle
│
├── platform-common/          Canonical Schema, Exception, Serialization dùng chung
│   ├── event/
│   ├── exception/
│   └── serialization/
│
├── ingestion-service/        High-throughput Ingestion (Java 21 Virtual Threads)
│   ├── controller/LogIngestionController.java
│   ├── service/DataMaskingService.java
│   └── kafka/KafkaLogProducer.java
│
├── indexer-worker/           Kafka Consumer -> Bulk Elasticsearch (item-level handling)
│   ├── consumer/KafkaLogConsumer.java
│   └── es/ElasticsearchBulkIndexer.java
│
├── analytics-engine/         Kafka Streams Engine cho Alerting (Tumbling Window)
│   └── streams/ErrorWindowStreamProcessor.java
│
├── alert-consumer/           Đọc system-alerts, ghi PostgreSQL (mục 6.1)
│   └── consumer/AlertPersistenceConsumer.java
│
├── notification-dispatcher/  Đọc system-alerts, gửi Slack/Telegram (mục 6.2)
│   └── dispatcher/NotificationDispatcher.java
│
└── core-app/                 REST Management APIs & WebSocket Dashboard
    ├── security/JwtAuthFilter.java
    ├── service/ServiceRegistryService.java
    ├── service/AlertManagementService.java
    └── websocket/DashboardMetricsHandler.java
```

# **13\. Cam kết rõ ràng & Đánh đổi đã biết (Explicit Guarantees & Known Trade-offs) — bổ sung mới**

**Bổ sung (nên có):** tổng hợp lại các cam kết/giới hạn quan trọng đã nêu rải rác ở các mục trên, để dùng làm tài liệu tham chiếu nhanh khi trình bày kiến trúc hoặc khi phỏng vấn.

- Ordering: log cùng một serviceId có thứ tự tương đối được đảm bảo trong phạm vi partition Kafka (Version 1, mục 3.1); không đảm bảo thứ tự toàn cục giữa các service. Đường retry (mục 3.3) có thể làm lệch thứ tự ghi vào Elasticsearch so với đường xử lý trực tiếp. Timestamp trong log event luôn là nguồn xác định thứ tự thời gian có thẩm quyền, không phụ thuộc thứ tự ghi vật lý.
- Idempotency: đảm bảo tại tầng Elasticsearch qua thao tác create với \_id = eventId (mục 5.2); 409 Conflict được coi là thành công về mặt idempotent, không phải lỗi.
- Data loss: không mất dữ liệu sau khi Kafka đã ACK (khớp SRS NFR-REL-01), trong phạm vi các failure scenario đã liệt kê tại mục 9.
- Rate limiting: chính xác toàn cục khi Redis khả dụng; suy giảm về ngưỡng thận trọng theo từng instance khi Redis không khả dụng (mục 4.2) — không tắt hoàn toàn.
- Benchmark & số liệu hiệu năng: mọi con số công bố chính thức (ví dụ TPS, latency) chỉ được ghi nhận SAU KHI đo thực tế bằng JMeter/Load Test, không đặt trước một con số mục tiêu như một kết quả đã đạt được. Trước khi benchmark, tài liệu chỉ nêu mục tiêu (target), ví dụ: "Target: ≥ 10,000 log events/giây"; sau benchmark mới công bố "Measured: X events/giây, P95: Y ms, P99: Z ms" — kể cả khi số đo thực tế thấp hơn mục tiêu, đây vẫn là số liệu có giá trị vì đi kèm khả năng giải thích bottleneck thực tế.

# **14\. Lộ trình triển khai dự án (Implementation Roadmap)**

### **Bước 1 — Hạ tầng Local**

Chạy docker-compose.yml khởi tạo PostgreSQL, Kafka (KRaft, không Zookeeper), Elasticsearch (single-node), Redis.

### **Bước 2 — Database & Ingestion**

Chạy DDL PostgreSQL (mục 7, đã gồm service_api_keys, alert_rule_channels). Viết Ingestion Service (Java 21, bật spring.threads.virtual.enabled=true, phạm vi VT theo mục 2.1). Test nạp log qua REST API → đẩy vào Kafka raw-logs.

### **Bước 3 — Elasticsearch Indexer**

Apply Index Template logs-template (pattern logs-\*, mục 5.1). Viết Indexer Worker đọc lô, Bulk create với \_id = eventId, xử lý item-level failure (mục 5.3), chỉ commit offset sau khi toàn batch được định tuyến xong.

### **Bước 4 — Retry/DLQ**

Triển khai chuỗi topic logs.retry.1s/5s/30s → logs.dlq kèm đầy đủ metadata header (mục 3.3).

### **Bước 5 — Stream Processing & Alerting**

Viết Kafka Streams (Tumbling Window 1 phút, không dùng Virtual Threads). Viết Alert Consumer ghi PostgreSQL (mục 6.1) và Notification Dispatcher (Slack/Telegram, mục 6.2) như hai module độc lập, kết hợp Redis Lock có giải phóng khi gửi thất bại (mục 4.3).

### **Bước 6 — Management API & Dashboard**

Viết API Search Log trên Elasticsearch, JWT Auth, Service Registry (bao gồm luồng service_api_keys). Core App tự đẩy WebSocket/STOMP cho Dashboard, không qua Notification Dispatcher (mục 6.2).

### **Bước 7 — Platform Self-Observability**

Thu thập các metric tại mục 10 cho từng thành phần, chuẩn bị dữ liệu cho Dashboard giám sát nội bộ.

### **Bước 8 — Benchmarking & Proof**

Chạy JMeter Load Test và Chaos Test (tắt Elasticsearch) để ghi nhận số liệu thực tế (Measured, không phải mục tiêu) đưa vào tài liệu/CV, theo nguyên tắc tại mục 13.

# **15\. Checklist trước khi bắt đầu code (Ready-to-Implement)**

Trạng thái sau khi hoàn thành SDD v1.1 — toàn bộ mục 🔴 và 🟡 dưới đây đã được áp dụng vào các mục tương ứng ở trên.

## **🔴 Bắt buộc — đã áp dụng**

- Redis Token Bucket: sửa công thức + độ chính xác millisecond (mục 4.1)
- Elasticsearch index pattern logs-\* (mục 5.1)
- Bulk item-level failure handling (mục 5.3)
- Elasticsearch idempotency dùng create + eventId bất biến (mục 5.2)
- Retry có delay theo chuỗi topic (mục 3.3)
- DLQ metadata đầy đủ header (mục 3.3)
- Alert Persistence Consumer (mục 6.1)
- WebSocket tách khỏi Notification Dispatcher (mục 6.2)
- Redis failure strategy — local fallback thận trọng thay vì bypass (mục 4.2)
- Kafka client failure retry có backoff + jitter (mục 9)
- Nhất quán KRaft/Zookeeper — chọn KRaft, bỏ Zookeeper (mục 11)
- alert_rules.created_by là FK tới users(id) (mục 7.3)
- Chuẩn hóa notification channel thành bảng alert_rule_channels (mục 7.3)

## **🟡 Nên có — đã áp dụng**

- API key rotation qua bảng service_api_keys (mục 7.2)
- Structured field masking + regex fallback (mục 8)
- Platform self-monitoring — danh mục metric theo thành phần (mục 10)
- Elasticsearch ILM: phân biệt rõ local single-node vs production topology (mục 5.4)
- Cam kết ordering tường minh (mục 3.1, tổng hợp tại mục 13)

## **🟢 Có thể để sau — giữ nguyên ngoài phạm vi MVP (không triển khai ở giai đoạn này)**

- Multi-region
- Kubernetes
- Kafka MirrorMaker
- Elasticsearch cross-cluster replication
- Distributed tracing của chính platform

_Giữ nguyên quyết định của vòng review: không đưa các hạng mục 🟢 vào MVP để tránh phình phạm vi trước khi có phiên bản chạy được._

_Sau khi hoàn thành SDD v1.1 (tài liệu này), kiến trúc được coi là "Ready to Implement". Bước tiếp theo là LLD (Low-Level Design): package structure → class diagram → entity → DTO → repository → service → Kafka producer/consumer → Elasticsearch indexer → Redis Lua script → API contract → test strategy — trước khi bắt đầu viết code._

Có. Tôi đã xem lại **SDD_v1.1_Observability_Platform.docx** sau khi bạn sửa. Lần này kết luận của tôi khác v1.0 khá rõ:

# 🟢 SDD v1.1 đã đủ để bắt đầu triển khai

Tài liệu hiện tại đã ghi rõ trạng thái **Ready-to-Implement**, đã xử lý toàn bộ nhóm blocker trước đó: Kafka semantics, retry/DLQ, Redis atomicity/fallback, Elasticsearch idempotency & bulk failure, Alert Consumer, WebSocket ownership, DB normalization, KRaft, API-key rotation, structured masking, self-monitoring và ordering.

Vì vậy:

**Bạn không cần quay lại sửa SRS và cũng không cần tiếp tục kéo dài System Design.**

Nhưng có một điểm rất quan trọng:

**"Ready-to-Implement" không có nghĩa là "mọi class/code đã được thiết kế."**

Bạn vẫn còn **một giai đoạn kỹ thuật rất nên làm trước khi code: LLD (Low-Level Design).**

# 1\. Vậy hiện tại project đang ở đâu?

Tôi sẽ đặt toàn bộ quy trình của project như sau:

```
                    REQUIREMENT
                         │
                         ▼
                  ┌─────────────┐
                  │ SRS v2.1    │
                  │ WHAT        │
                  └──────┬──────┘
                         │
                         ▼
                  ┌─────────────┐
                  │ SDD v1.1    │
                  │ HOW         │
                  └──────┬──────┘
                         │
                         ▼
                ⭐ BẠN ĐANG Ở ĐÂY
                         │
                         ▼
                  ┌─────────────┐
                  │ LLD         │
                  │ HOW EXACTLY │
                  └──────┬──────┘
                         │
                         ▼
                     CODING
                         │
                         ▼
                    UNIT TEST
                         │
                         ▼
                 INTEGRATION TEST
                         │
                         ▼
                 LOAD / CHAOS TEST
                         │
                         ▼
                    BENCHMARK
                         │
                         ▼
                  RELEASE / DEMO
```

SDD v1.1 của bạn thậm chí đã tự xác định bước tiếp theo là LLD, gồm package structure, class diagram, entity, DTO, repository, service, Kafka producer/consumer, Elasticsearch indexer, Redis Lua, API contract và test strategy.

# 2\. Có bắt buộc phải làm LLD không?

Nếu mục tiêu của bạn chỉ là:

"Tôi muốn code một project chạy được."

thì **không bắt buộc**.

Nhưng với mục tiêu của project này là:

**System-Level Backend Project + dùng làm portfolio/CV + thể hiện khả năng System Design**

thì tôi **rất khuyến nghị làm LLD**.

Bởi SDD hiện tại mới nói:

```
Indexer Worker
Kafka Consumer
Elasticsearch
```

Nhưng khi code bạn sẽ lập tức phải quyết định:

```
KafkaLogConsumer
      │
      ▼
LogBatchProcessor
      │
      ▼
ElasticsearchBulkIndexer
      │
      ├── SuccessHandler
      ├── RetryHandler
      └── DLQHandler
```

Đó chính là LLD.

# 3\. LLD của project này nên gồm những gì?

Không cần viết một tài liệu 100 trang.

Tôi đề xuất **LLD v1.0 khoảng 8 phần**.

## 3.1. Project / Module Structure

Chốt chính xác:

```
observability-platform/
│
├── pom.xml
│
├── platform-common/
│
├── ingestion-service/
│
├── indexer-worker/
│
├── analytics-engine/
│
├── alert-consumer/
│
├── notification-dispatcher/
│
└── core-app/
```

SDD đã có hướng này, bao gồm cả Alert Consumer và các streaming worker độc lập.

LLD cần đi sâu thêm:

```
ingestion-service
└── src/main/java/...
    ├── controller
    ├── service
    ├── security
    ├── kafka
    ├── ratelimit
    ├── masking
    ├── dto
    ├── exception
    └── config
```

# 4\. Entity Design

Bạn cần chốt class/entity trước khi code:

```
User
Role
UserRole
Service
ServiceApiKey
AlertRule
AlertRuleChannel
Alert
AuditLog
```

Ví dụ:

```
Service
──────────────
id
name
team
environment
status
createdAt
updatedAt
```

và:

```
ServiceApiKey
──────────────
id
serviceId
keyPrefix
keyHash
createdAt
expiresAt
revokedAt
lastUsedAt
```

LLD phải chốt:

- Java type
- nullable
- validation
- unique constraint
- FK
- index
- enum

# 5\. DTO Design

Đây là phần bạn **không nên bỏ qua**.

Ví dụ:

```
CreateServiceRequest
CreateServiceResponse
LoginRequest
LoginResponse
LogEventRequest
LogEventResponse
CreateAlertRuleRequest
AlertResponse
SearchLogRequest
SearchLogResponse
```

Đặc biệt:

```
LogEventRequest
```

không nên trực tiếp bind vào Elasticsearch document.

Flow phải là:

```
HTTP DTO
   ↓
Validation
   ↓
Domain / Canonical Event
   ↓
Kafka
   ↓
Elasticsearch
```

# 6\. Service Layer Design

Bạn cần chốt trách nhiệm từng service.

Ví dụ:

```
LogIngestionService
├── validate()
├── maskSensitiveData()
├── buildCanonicalEvent()
└── publish()
```

Không để:

```
Controller
   ↓
KafkaTemplate
```

rồi nhét toàn bộ business logic vào controller.

# 7\. Kafka LLD — phần quan trọng nhất

SDD đã chốt architecture:

```
raw-logs
system-alerts
logs.retry.1s
logs.retry.5s
logs.retry.30s
logs.dlq
```

và roadmap triển khai cũng đã xác định Indexer đọc batch, Bulk Create với \_id = eventId, item-level failure và chỉ commit sau khi batch được xử lý theo policy.

LLD cần chốt tiếp:

```
KafkaLogProducer
KafkaLogConsumer
RetryPublisher
DlqPublisher
```

và:

```
ConsumerRecord
     ↓
BatchProcessor
     ↓
BulkIndexer
     ↓
ItemResult
     ↓
Retry/DLQ
     ↓
Offset Commit
```

Đặc biệt phải xác định:

**Record nào được commit? Khi nào commit?**

Đây là nơi dễ tạo bug nhất của project.

# 8\. Redis LLD

SDD đã sửa Token Bucket sang atomic Lua + millisecond và local fallback thận trọng.

LLD cần biến thành:

```
RateLimiter
    │
    ▼
RedisRateLimiter
    │
    ▼
TokenBucketLuaScript
```

và:

```
AlertCooldownService
        │
        ▼
Redis
```

Chốt:

```
key format
TTL
Lua arguments
return values
exception handling
fallback behavior
```

# 9\. Elasticsearch LLD

Đây cũng là phần cần làm rõ trước code.

SDD đã chốt:

```
logs-*
eventId → _id
create
```

và item-level failure.

LLD cần chốt:

```
LogDocument
ElasticsearchBulkIndexer
ElasticsearchSearchService
ElasticsearchQueryBuilder
```

Mapping:

```
eventId       keyword
timestamp     date
serviceName   keyword
environment   keyword
level         keyword
message       text
traceId       keyword
spanId        keyword
statusCode    integer
durationMs    integer
```

và quan trọng:

```
Search API
    ↓
Query DTO
    ↓
Query Builder
    ↓
Elasticsearch
```

# 10\. API Contract

SRS đã có endpoint list khá đầy đủ, ví dụ:

```
POST /api/v1/telemetry/logs
POST /api/v1/telemetry/logs/batch
GET  /api/v1/logs
GET  /api/v1/logs/{id}
GET  /api/v1/traces/{traceId}
GET  /api/v1/alerts
...
```

Nhưng trước code bạn nên chốt **OpenAPI/Swagger contract**:

```
Request
Response
Status
Validation
Authentication
Authorization
Error schema
Pagination
Sorting
```

Ví dụ thống nhất một error model:

```
{
  "code": "SERVICE_NOT_FOUND",
  "message": "Service not found",
  "timestamp": "...",
  "path": "/api/v1/services/123",
  "traceId": "..."
}
```

# 11\. Sequence Diagram — tôi khuyên bắt buộc làm 6 cái

Không cần vẽ hàng chục diagram.

Chỉ cần:

### ① Ingestion

```
Microservice
     ↓
Ingestion API
     ↓
API Key
     ↓
Rate Limit
     ↓
Validation
     ↓
Masking
     ↓
Kafka
     ↓
202
```

### ② Elasticsearch Indexing

```
Kafka
 ↓
Indexer
 ↓
Bulk ES
 ↓
Success / Retry / DLQ
 ↓
Commit
```

### ③ Alert

```
Kafka
 ↓
Kafka Streams
 ↓
Window
 ↓
AlertEvent
 ↓
system-alerts
```

### ④ Notification

```
system-alerts
 ↓
Redis cooldown
 ↓
Slack/Telegram
```

### ⑤ Search

```
Client
 ↓
JWT
 ↓
Core App
 ↓
Query Builder
 ↓
Elasticsearch
```

### ⑥ Failure Recovery

```
ES DOWN
 ↓
Indexer pause
 ↓
Kafka buffer
 ↓
ES recovery
 ↓
resume
 ↓
process backlog
```

# 12\. Concurrency Design

Project này **bắt buộc** nên có một phần nhỏ về concurrency.

Chốt rõ:

```
Virtual Threads
      ↓
HTTP request concurrency
```

không phải:

```
Virtual Threads
      ↓
Kafka Streams
```

SDD v1.1 đã sửa chính xác phạm vi Virtual Threads và thừa nhận Kafka Producer vốn asynchronous.

LLD cần chốt thêm:

```
Thread model
Executor
Blocking calls
Connection pool
Concurrency limit
Backpressure
```

# 13\. Transaction Boundary

Đây là thứ tôi muốn bạn **chốt trước code**.

Ví dụ:

### Alert persistence

```
Kafka event
     ↓
@KafkaListener
     ↓
AlertService
     ↓
@Transactional
     ↓
PostgreSQL
     ↓
ACK
```

Nhưng:

### Ingestion

```
HTTP
 ↓
Kafka send
 ↓
ACK
 ↓
HTTP 202
```

**Không có transaction DB ở đây.**

Nếu không phân biệt rõ hai loại flow này, lúc code rất dễ đặt @Transactional sai chỗ.

# 14\. Sau LLD mới bắt đầu code

Tôi khuyên thứ tự:

```
SRS v2.1                 ✅ DONE
       ↓
SDD v1.1                 ✅ DONE
       ↓
LLD v1.0                 ← BẠN ĐANG Ở ĐÂY
       ↓
Infrastructure
       ↓
Database
       ↓
Common module
       ↓
Ingestion
       ↓
Kafka
       ↓
Indexer
       ↓
Elasticsearch
       ↓
Kafka Streams
       ↓
Alert
       ↓
Redis
       ↓
Notification
       ↓
Core API
       ↓
WebSocket
       ↓
Testing
       ↓
Benchmark
```

# 15\. Có cần thêm một giai đoạn "Architecture Review" không?

**Không cần.**

Bạn đã review SRS nhiều vòng và SDD cũng vừa qua một vòng sửa blocker.

Tôi không muốn bạn rơi vào vòng lặp:

```
Design
 ↓
Review
 ↓
Design
 ↓
Review
 ↓
Design
 ↓
...
```

Đây là lúc **dừng thiết kế và bắt đầu xây dựng**.

# 16\. Nhưng có một việc tôi phát hiện trong tài liệu mới cần lưu ý

SDD v1.1 ghi rõ:

"đã áp dụng toàn bộ 13 điểm blocker bắt buộc và 5 điểm nên có."

Và checklist cũng xác nhận các điểm này đã được đưa vào tài liệu.

Tôi đồng ý với trạng thái đó.

Tuy nhiên, **đừng hiểu "đã áp dụng trong SDD" = "đã được chứng minh bằng implementation."**

Ví dụ:

```
Redis Token Bucket
```

Hiện tại:

```
Design: ✅
Implementation: ❌
Test: ❌
```

Sau này:

```
Design: ✅
Implementation: ✅
Test: ✅
Benchmark: ✅
```

Mới thực sự hoàn thành.

# 17\. Definition of Done cho từng feature

Từ bây giờ tôi khuyên bạn dùng quy tắc:

```
Feature
  │
  ├── Design
  ├── Implementation
  ├── Unit Test
  ├── Integration Test
  └── Failure Test
```

Ví dụ Kafka:

```
Kafka Producer
    │
    ├── Code
    ├── Unit Test
    ├── Integration Test
    ├── Kafka failure test
    └── Duplicate event test
```

Không phải:

```
Code xong
→ coi như Done
```

# 18\. Cuối cùng: Project của bạn đang ở mức nào?

Tôi đánh giá hiện tại:

| **Phase**           | **Status**  |
| ------------------- | ----------- |
| Business Analysis   | 🟢 Done     |
| SRS                 | 🟢 Done     |
| HLD                 | 🟢 Done     |
| System Design       | 🟢 Done     |
| Architecture Review | 🟢 Done     |
| LLD                 | 🟡 **Next** |
| Coding              | ⏳          |
| Unit Testing        | ⏳          |
| Integration Testing | ⏳          |
| Load Testing        | ⏳          |
| Chaos Testing       | ⏳          |
| Benchmark           | ⏳          |
| Documentation/CV    | ⏳          |

### Vì vậy câu trả lời chính xác nhất là

**Chưa code ngay. Nhưng cũng không còn cần sửa System Design nữa.**

Bạn chỉ còn **LLD** trước khi bước vào implementation.

Và LLD của project này **không cần quá dài**. Chỉ cần chốt chắc:

**Module structure → Entity → DTO → Service → Repository → Kafka Producer/Consumer → Retry/DLQ → Redis → Elasticsearch → API contract → Security → Sequence diagrams → Transaction/Concurrency boundaries → Test structure.**

Sau khi chốt được những thứ đó, tôi sẽ coi project là:

# 🟢 READY TO CODE

và lúc đó chúng ta có thể triển khai **từng module một theo đúng design**, thay vì vừa code vừa thay đổi architecture.

Đặc biệt, roadmap trong SDD v1.1 của bạn cũng đã xác định thứ tự triển khai từ infrastructure → database/ingestion → Elasticsearch indexer → retry/DLQ → Kafka Streams/alert → management API/dashboard → self-observability → benchmark.