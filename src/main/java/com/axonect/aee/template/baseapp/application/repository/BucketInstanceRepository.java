package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.BucketInstance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.QueryHint;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface BucketInstanceRepository extends JpaRepository<BucketInstance,Long> {
    Optional<BucketInstance> findFirstByServiceIdOrderByPriorityDesc(Long serviceId);

    /**
     * Find bucket instances by service ID.
     * OPTIMIZED: Uses index on SERVICE_ID for fast lookups.
     */
    @QueryHints(@QueryHint(name = "org.hibernate.fetchSize", value = "100"))
    List<BucketInstance> findByServiceId(Long serviceId);

    /**
     * Batch fetch bucket instances for multiple services.
     * CRITICAL FOR 10M+ RECORDS: Uses IN clause with index to minimize database round-trips.
     */
    @Query(value = "SELECT /*+ INDEX(b idx_bucket_instance_service_id) */ " +
            "b.* FROM BUCKET_INSTANCE b WHERE b.SERVICE_ID IN :serviceIds",
            nativeQuery = true)
    @QueryHints(@QueryHint(name = "org.hibernate.fetchSize", value = "100"))
    List<BucketInstance> findByServiceIdIn(@Param("serviceIds") Set<Long> serviceIds);

    Page<BucketInstance> findAllBy(Pageable pageable);

    /**
     * Find expired bucket instances for deletion.
     */
    @Query(value = "SELECT /*+ INDEX(b idx_bucket_instance_expiration) FIRST_ROWS(100) */ " +
            "b.* FROM BUCKET_INSTANCE b WHERE b.EXPIRATION IS NOT NULL AND b.EXPIRATION < :today",
            countQuery = "SELECT /*+ INDEX(b idx_bucket_instance_expiration) */ " +
            "COUNT(*) FROM BUCKET_INSTANCE b WHERE b.EXPIRATION IS NOT NULL AND b.EXPIRATION < :today",
            nativeQuery = true)
    Page<BucketInstance> findExpiredBuckets(@Param("today") LocalDateTime today, Pageable pageable);
}