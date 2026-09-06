package com.thinhbui303.observability.core.repository;

import com.thinhbui303.observability.core.domain.AlertRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlertRuleRepository extends JpaRepository<AlertRuleEntity, Long> {

    @Query("SELECT r FROM AlertRuleEntity r WHERE (:serviceId IS NULL OR r.service.id = :serviceId) " +
           "AND (:environment IS NULL OR r.environment = :environment)")
    List<AlertRuleEntity> search(@Param("serviceId") String serviceId, @Param("environment") String environment);
}