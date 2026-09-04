CREATE TABLE audit_logs (
  id BIGSERIAL PRIMARY KEY,
  username VARCHAR(50) NOT NULL,           -- snapshot, không FK
  action VARCHAR(100) NOT NULL,
  resource_target VARCHAR(100) NOT NULL,
  ip_address VARCHAR(45) NOT NULL,
  result_status VARCHAR(20) NOT NULL,       -- SUCCESS, FAILED
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
