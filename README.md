# Enterprise Log Aggregation & Real-time Observability Platform

## Phase 1: Infrastructure & Project Bootstrap

Dự án này sử dụng Maven multi-module architecture.

### Các modules:
- `platform-common`: Chứa các Canonical models và Enums.
- `platform-db`: Chứa Flyway migration scripts.
- `core-app`: Entry point quản lý system, thực thi Flyway migrations.
- `ingestion-service`, `indexer-worker`, `analytics-engine`, `alert-consumer`, `notification-dispatcher`: Các services xử lý nghiệp vụ (hiện tại chỉ dựng khung).

---

## Hướng dẫn Smoke Test Hạ tầng (Local)

Để đảm bảo hạ tầng đã được setup thành công, hãy làm theo các bước sau:

### Bước 1: Khởi động các container phụ thuộc

1. Copy file `.env.example` thành `.env` (có thể giữ nguyên các giá trị mặc định cho local dev).
2. Chạy lệnh:
   ```bash
   docker-compose up -d
   ```
3. Kiểm tra trạng thái của các container:
   ```bash
   docker ps
   ```
   *Yêu cầu*: Cả 4 container (`obs_postgres`, `obs_kafka`, `obs_redis`, `obs_elasticsearch`) phải ở trạng thái `(healthy)`.
   
   *(Lưu ý: Elasticsearch và Kafka có thể mất khoảng 30s - 1 phút để khởi động và pass healthcheck lần đầu).*

### Bước 2: Build toàn bộ project

Chạy lệnh maven để đảm bảo code compile thành công:
```bash
mvn clean install
```
*Yêu cầu*: Build báo `BUILD SUCCESS` cho tất cả modules.

### Bước 3: Chạy Flyway Migration

Chạy `core-app` để trigger Flyway migration:
```bash
cd core-app
mvn spring-boot:run
```
*Yêu cầu*: Trong log khởi động, bạn sẽ thấy Flyway chạy tuần tự từ `V1` đến `V5`. Ứng dụng khởi động thành công (Tomcat started on port 8080). Sau khi ứng dụng báo started, bạn có thể tắt bằng `Ctrl+C`.

### Bước 4: Kiểm tra Database Schema

Dùng `psql` hoặc bất kỳ Database Client (DBeaver, DataGrip) để kết nối vào PostgreSQL:
- **Host**: `localhost`
- **Port**: `5432`
- **DB Name**: `observability`
- **User**: `obs_user` (theo .env)
- **Password**: `obs_password` (theo .env)

Chạy lệnh SQL sau để xác nhận các bảng đã được tạo:
```sql
SELECT table_name 
FROM information_schema.tables 
WHERE table_schema = 'public';
```
*Yêu cầu*: Phải hiển thị các bảng: `users`, `roles`, `user_roles`, `services`, `service_api_keys`, `alert_rules`, `alert_rule_channels`, `alerts`, `audit_logs` và `flyway_schema_history`.

---

## Slice 4: Alerting Pipeline (alert-consumer + notification-dispatcher)

Topic `system-alerts` feed hai consumer độc lập trong hai module riêng:
- **`alert-consumer`** (group `alert-persistence-group`): Persist mỗi alert xuống PostgreSQL theo cách idempotent.
- **`notification-dispatcher`** (group `notification-dispatch-group`): Gửi thông báo theo cấu hình `alert_rule_channels` (hỗ trợ SLACK / TELEGRAM / WEBHOOK; WEBSOCKET deferred). Dùng Redis cooldown window 300s theo `(service, environment, rule)` để tránh spam.

### Quyết định Business-key Dedup

Bảng `alerts` coi `(rule_id, service_id, environment, window_start)` là **một business alert duy nhất** (`uq_alerts_business_key` trong `V4__create_alerts.sql`).
- Emit trùng với `alertId` mới → tăng `occurrence_count + 1` trên row hiện có.
- Emit trùng exact `alertId` → idempotent skip.

Slice 4 **không cần thêm migration mới** (V1–V7 vẫn đầy đủ).

### Lưu ý Mock Webhook trong Tests

Các test dùng `MockRestServiceServer` (spring-test) để mock webhook client. **Không có token Slack/Telegram thật hay gọi HTTP thực** trong test. Ở production, cột `target` chứa webhook URL thật.

### Kiểm tra Regression

Toàn bộ reactor phải pass khi chạy:
```bash
mvn clean test -o
```
*Yêu cầu*: `BUILD SUCCESS`. Lưu ý integration tests cần docker-compose infra đang chạy (`obs_postgres`, `obs_kafka`, `obs_redis`, `obs_elasticsearch`).

---

## Slice 5: Service Registry, Alert Management & Audit Log (core-app API)

### 1. RBAC Matrix

Các endpoint mới của Slice 5 theo đúng SRS 13.2. `ROLE_` prefix được dùng trong `@PreAuthorize` (method security đã bật qua `@EnableMethodSecurity`).

| Endpoint | Method | Roles |
|---|---|---|
| `/api/v1/services` | POST | `ROLE_ADMIN` |
| `/api/v1/services` | GET | `ROLE_ADMIN`, `ROLE_DEVOPS` |
| `/api/v1/services/{id}/status` | PATCH | `ROLE_ADMIN` |
| `/api/v1/alert-rules` | POST / PUT / PATCH enabled / DELETE / GET | `ROLE_ADMIN`, `ROLE_DEVOPS` |
| `/api/v1/alerts` | GET | mọi role đã xác thực |
| `/api/v1/alerts/{id}/acknowledge` | PATCH | `ROLE_ADMIN`, `ROLE_DEVOPS`, `ROLE_DEVELOPER` |
| `/api/v1/alerts/{id}/resolve` | PATCH | `ROLE_ADMIN`, `ROLE_DEVOPS` |
| `/api/v1/audit-logs` | GET | `ROLE_ADMIN`, `ROLE_DEVOPS` |

### 2. Audit Semantics

Mọi mutation đều ghi audit qua `AuditLogService`:
- **`recordSuccess`** chạy trong **REQUIRED** — join transaction của mutation → nếu mutation rollback thì row audit cũng rollback theo (atomic, BR-009).
- **`recordFailure`** chạy trong **REQUIRES_NEW** — transaction riêng, commit độc lập → sống sót khi transaction ngoài rollback (ghi row FAILED khi ném `ConflictException`/`BadRequestException` sau call).
- FAILED rows chỉ được ghi trong phạm vi kiểm tra trạng thái/business (transition không hợp lệ, liên hệ nhân quả), không phải mọi exception.
- IP client qua `OperationContext.resolveIp`; flag `app.audit.trust-forwarded: false` mặc định có nghĩa **không** tin tưởng `X-Forwarded-For` từ request (tránh spoof).

### 3. Locked Decisions (Slice 5)

1. **DEVOPS scope**: audit/read và alert management mở cho `ROLE_DEVOPS` (không gói gọn ở ADMIN).
2. **OPEN ≡ TRIGGERED** state machine: `TRIGGERED/OPEN → ACKNOWLEDGED → RESOLVED`. `OPEN` chỉ là alias hiển thị của trạng thái `TRIGGERED` khi chưa acknowledge.
3. **Slug service id**: `services.id` sinh từ slug của `name` (`SlugBuilder`); nếu trùng `id`, tự động hậu tố `-2`, `-3`, … Chỉ `id` là unique (LLD §2) — **không** có business rule về uniqueness của `name`, nên không có app-level name check.

### 4. API Key Lifecycle

- Key sinh **một lần duy nhất** lúc tạo service: prefix `sk_` + 32 bytes ngẫu nhiên (Base64 URL-safe). **Plaintext chỉ trả về đúng lúc creation** (response `data.plainKey`), giữ nguyên `key_prefix` (12 ký tự) + `key_hash` (SHA-256 hex).
- `key_hash` sinh bằng `ApiKeyHashUtil.hash` (dùng chung từ `platform-common`).
- Ingestion đã sẵn enforce `s.status = 'ACTIVE'` trong `ApiKeyValidator.java:30` — service ở trạng thái khác `ACTIVE` không thể gửi log.

### 5. Analytics Rule-Cache Note

Thay đổi rule trong `analytics-engine` được phản ánh **trong ≤ 30 s** (by design): `analytics.rule.cache.refresh-rate: 30000` (test dùng 2000 ms). Không phải real-time.

### 6. `AlertColumnContract` Rationale

Hai module độc lập (`alert-consumer` và `core-app`) đều map lên bảng `alerts` qua entity riêng. `AlertColumnContract.ALERTS` trong `platform-common` là **single source of truth** cho `V4__create_alerts.sql`; mỗi module có một contract test đối chiếu `information_schema.columns` field-by-field (`AlertColumnContractVsConsumerEntityTest`, `AlertColumnContractVsCoreEntityTest`) bắt sớm hiện tượng drift giữa migration và entity.

---

## Slice 6: WebSocket Dashboard (STOMP)

### 1. Locked Decisions (Slice 6)

1. **Nguồn dữ liệu Alert real-time**: `core-app` có một `AlertBroadcastConsumer` độc lập (`dashboard-broadcast-group`) đọc từ topic `system-alerts` và đẩy trực tiếp qua WebSocket (không ghi vào DB, đảm bảo separation of concerns với `alert-consumer`).
2. **Nguồn dữ liệu Metrics**: `DashboardMetricsScheduler` định kỳ (5s) query Elasticsearch để tính Logs/sec và Error Rate. Error Rate sử dụng rolling window **5 phút** (cố định) để đảm bảo độ tin cậy của chỉ số.
3. **Service Health**: Đánh giá theo thứ tự deterministic tuyệt đối:
   - `UNAVAILABLE`: lastSeen >= 2 phút.
   - `DEGRADED`: lastSeen >= 30s HOẶC errorRate >= 5%.
   - `HEALTHY`: Các trường hợp còn lại.
4. **Xác thực STOMP**: Sử dụng `JwtStompChannelInterceptor` chặn tại thời điểm gửi `CONNECT` frame để xác thực token (re-use `JwtTokenProvider`).
5. **Dashboard RBAC**: Cho phép truy cập từ role `VIEWER` trở lên, nhất quán với các API Read-only của hệ thống.

### 2. Client Reconnect & Caching

Hệ thống lưu giữ một in-memory snapshot (`AtomicReference`) chứa dữ liệu metrics và service-health mới nhất. Khi một STOMP client vừa gửi lệnh `SUBSCRIBE`, server sẽ lập tức đẩy snapshot này về cho **riêng session đó**, giúp client thấy ngay dữ liệu mà không cần phải chờ đến chu kỳ broadcast (5s) kế tiếp.
