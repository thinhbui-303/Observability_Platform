CREATE TABLE alerts (
  id VARCHAR(50) PRIMARY KEY,             -- UUID-v4, immutable
  rule_id BIGINT REFERENCES alert_rules(id),
  service_id VARCHAR(50) REFERENCES services(id),
  environment VARCHAR(20) NOT NULL,
  window_start TIMESTAMPTZ NOT NULL,       -- mốc bắt đầu Tumbling Window
  severity VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,             -- TRIGGERED, OPEN, ACKNOWLEDGED, RESOLVED
  triggered_at TIMESTAMPTZ NOT NULL,
  acknowledged_at TIMESTAMPTZ,
  resolved_at TIMESTAMPTZ,
  occurrence_count INT NOT NULL DEFAULT 1,
  -- Chốt quyết định LLD v1.1 mục 11.3 (đã để ngỏ, nay quyết định dứt điểm):
  -- ràng buộc DUY NHẤT chống trùng ở business-level, Alert Consumer bắt lỗi
  -- unique_violation và coi là idempotent success thay vì lỗi.
  CONSTRAINT uq_alerts_business_key UNIQUE (rule_id, service_id, environment, window_start)
);
