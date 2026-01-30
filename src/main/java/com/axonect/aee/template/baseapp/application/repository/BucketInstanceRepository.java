package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.BucketInstance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface BucketInstanceRepository extends JpaRepository<BucketInstance,Long> {
    Optional<BucketInstance> findFirstByServiceIdOrderByPriorityDesc(Long serviceId);

    List<BucketInstance> findByServiceId(Long serviceId);

    List<BucketInstance> findByServiceIdIn(Set<Long> serviceIds);

    Page<BucketInstance> findAllBy(Pageable pageable);

    @Query("SELECT b FROM BucketInstance b WHERE b.expiration IS NOT NULL AND b.expiration < :today")
    Page<BucketInstance> findExpiredBuckets(@Param("today") LocalDateTime today, Pageable pageable);
}