package com.thinhbui303.observability.alert.repository;

import com.thinhbui303.observability.alert.entity.AlertEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface AlertRepository extends JpaRepository<AlertEntity, String> {
    Optional<AlertEntity> findByRuleIdAndServiceIdAndEnvironmentAndWindowStart(
            Long ruleId, String serviceId, String environment, Instant windowStart);
}
