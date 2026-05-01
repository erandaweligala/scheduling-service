package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.BucketInstanceHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BucketInstanceHistoryRepository extends JpaRepository<BucketInstanceHistory, Long> {

    List<BucketInstanceHistory> findByOriginalId(Long originalId);

    List<BucketInstanceHistory> findByServiceId(Long serviceId);

    List<BucketInstanceHistory> findByBatchId(String batchId);
}
