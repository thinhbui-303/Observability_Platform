package com.thinhbui303.observability.core.repository;

import com.thinhbui303.observability.core.domain.AuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    @Query("SELECT a FROM AuditLogEntity a " +
           "WHERE (:action IS NULL OR a.action = :action) " +
           "AND (:username IS NULL OR a.username = :username) " +
           "AND (:resourceTarget IS NULL OR a.resourceTarget LIKE CONCAT('%', :resourceTarget, '%')) " +
           "ORDER BY a.id DESC")
    List<AuditLogEntity> search(@Param("action") String action,
                                @Param("username") String username,
                                @Param("resourceTarget") String resourceTarget);
}