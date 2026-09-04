CREATE TABLE alert_rules (
  id BIGSERIAL PRIMARY KEY,
  rule_name VARCHAR(100) NOT NULL,
  service_id VARCHAR(50) REFERENCES services(id),
  environment VARCHAR(20) NOT NULL,
  condition_type VARCHAR(50) NOT NULL,   -- ERROR_SPIKE, PATTERN_MATCH
  threshold_value INT NOT NULL,
  window_seconds INT NOT NULL,
  severity VARCHAR(20) NOT NULL,          -- CRITICAL, HIGH, MEDIUM
  is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_by BIGINT REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE alert_rule_channels (
  id BIGSERIAL PRIMARY KEY,
  alert_rule_id BIGINT NOT NULL REFERENCES alert_rules(id) ON DELETE CASCADE,
  channel_type VARCHAR(20) NOT NULL,   -- SLACK, TELEGRAM, WEBHOOK, WEBSOCKET
  target VARCHAR(255),
  enabled BOOLEAN NOT NULL DEFAULT TRUE
);
