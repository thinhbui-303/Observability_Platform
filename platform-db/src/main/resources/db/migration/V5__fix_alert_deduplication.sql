-- Xóa bỏ constraint chống trùng cũ (dính liền với window_start)
ALTER TABLE alerts DROP CONSTRAINT uq_alerts_business_key;

-- Tạo lại Unique Index chống trùng mới:
-- CHỈ cấm trùng lặp (rule_id, service_id, environment) đối với các Alert đang MỞ (TRIGGERED hoặc ACKNOWLEDGED).
-- Khi Alert bị RESOLVED, Index này sẽ bỏ qua, cho phép sinh ra một Alert MỚI cho cùng 1 lỗi trong tương lai.
CREATE UNIQUE INDEX uq_alerts_active_business_key 
ON alerts (rule_id, service_id, environment) 
WHERE status IN ('TRIGGERED', 'ACKNOWLEDGED');
