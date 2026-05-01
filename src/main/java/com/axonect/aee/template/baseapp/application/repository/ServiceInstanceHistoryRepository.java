package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.ServiceInstanceHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ServiceInstanceHistoryRepository extends JpaRepository<ServiceInstanceHistory, Long> {

    List<ServiceInstanceHistory> findByOriginalId(Long originalId);

    List<ServiceInstanceHistory> findByBatchId(String batchId);
}
