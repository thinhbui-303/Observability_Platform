package com.thinhbui303.observability.core.repository;

import com.thinhbui303.observability.core.domain.AlertEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlertRepository extends JpaRepository<AlertEntity, String> {

    @Query("SELECT a FROM AlertEntity a WHERE (:serviceId IS NULL OR a.serviceId = :serviceId) " +
           "AND (:environment IS NULL OR a.environment = :environment) " +
           "AND (:status IS NULL OR a.status = :status)")
    List<AlertEntity> search(@Param("serviceId") String serviceId,
                             @Param("environment") String environment,
                             @Param("status") String status);

    long countByStatusIn(List<String> statuses);
}