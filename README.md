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
