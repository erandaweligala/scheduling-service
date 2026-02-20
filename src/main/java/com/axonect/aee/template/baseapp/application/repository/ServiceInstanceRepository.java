package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.ServiceInstance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface ServiceInstanceRepository extends JpaRepository<ServiceInstance, Long> {

    Page<ServiceInstance> findByServiceCycleEndDateBetween(
            LocalDateTime startDateTime,
            LocalDateTime endDateTime,
            Pageable pageable);
}
