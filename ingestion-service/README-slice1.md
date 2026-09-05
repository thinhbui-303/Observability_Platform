# Hướng dẫn Test Ingestion API (Slice 1)

## 1. Tạo API Key
Bạn có thể tự tạo một API Key gốc, ví dụ: `epl_test_abc123`.
Sau đó, băm (hash) chuỗi này bằng thuật toán SHA-256 để lưu vào cơ sở dữ liệu:
```bash
echo -n "epl_test_abc123" | sha256sum
# Kết quả (ví dụ): c10597df85194453dffe77261ad980d266cab4791fe224ab08d42ab2f5504dcf
```

## 2. Seed dữ liệu vào Database
Mở SQL Shell (psql) và kết nối tới database `observability` (lưu ý dùng Server là `127.0.0.1`):
```sql
-- Tạo service test
INSERT INTO services (id, name, team_owner, environment, status, created_at) 
VALUES ('payment-service', 'Payment Service', 'backend-team', 'development', 'ACTIVE', now());

-- Tạo API key cho service này với key_hash từ bước 1
INSERT INTO service_api_keys (service_id, key_prefix, key_hash, created_at)
VALUES ('payment-service', 'epl_test', 'c10597df85194453dffe77261ad980d266cab4791fe224ab08d42ab2f5504dcf', now());
```

## 3. Test Ingestion API bằng cURL
Gửi một request mẫu kèm Header `X-API-Key` (dùng API Key CHƯA hash):
```bash
curl -X POST http://localhost:8081/api/v1/telemetry/logs \
  -H "Content-Type: application/json" \
  -H "X-API-Key: epl_test_abc123" \
  -d '{
    "timestamp": "2026-09-05T10:00:00Z",
    "level": "INFO",
    "message": "User login success - token=123456",
    "metadata": {
      "userId": "u-001"
    }
  }'
```

Kết quả trả về sẽ là `202 Accepted`. Kiểm tra raw-logs topic trong Kafka, bạn sẽ thấy `serviceName` là `payment-service` và `environment` là `development`. Chuỗi `token=123456` trong `message` sẽ bị che thành `token=[REDACTED]`.
