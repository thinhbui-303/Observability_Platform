package com.thinhbui303.observability.core.repository;

import com.thinhbui303.observability.core.domain.ServiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ServiceRepository extends JpaRepository<ServiceEntity, String> {
}