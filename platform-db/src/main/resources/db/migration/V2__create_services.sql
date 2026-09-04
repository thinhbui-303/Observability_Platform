CREATE TABLE services (
  id VARCHAR(50) PRIMARY KEY,          -- vd 'payment-service'
  name VARCHAR(100) NOT NULL,
  team_owner VARCHAR(50) NOT NULL,
  environment VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE service_api_keys (
  id BIGSERIAL PRIMARY KEY,
  service_id VARCHAR(50) NOT NULL REFERENCES services(id) ON DELETE CASCADE,
  key_prefix VARCHAR(12) NOT NULL,
  key_hash VARCHAR(255) NOT NULL,       -- SHA-256, không lưu plain-text
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMPTZ,
  revoked_at TIMESTAMPTZ,
  last_used_at TIMESTAMPTZ
);
