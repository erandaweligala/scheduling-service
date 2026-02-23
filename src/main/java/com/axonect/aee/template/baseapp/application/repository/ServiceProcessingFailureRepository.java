package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.repo.ServiceProcessingFailure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for managing service processing failures.
 * Provides methods to track and query failed service processing attempts.
 */
public interface ServiceProcessingFailureRepository extends JpaRepository<ServiceProcessingFailure, Long> {

    /**
     * Find all failures for a specific username
     */
    List<ServiceProcessingFailure> findByUsername(String username);

    /**
     * Find all failures by processing status
     */
    List<ServiceProcessingFailure> findByProcessingStatus(String processingStatus);

    /**
     * Find all failures for a specific service instance
     */
    List<ServiceProcessingFailure> findByServiceInstanceId(Long serviceInstanceId);

    /**
     * Find failures that occurred within a date range
     */
    @Query("SELECT f FROM ServiceProcessingFailure f WHERE f.failureDate >= :startDate AND f.failureDate <= :endDate")
    List<ServiceProcessingFailure> findByFailureDateBetween(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    /**
     * Find pending retry failures
     */
    List<ServiceProcessingFailure> findByProcessingStatusAndRetryCountLessThan(
            String processingStatus, Integer maxRetries);

    /**
     * Find failures by batch ID
     */
    List<ServiceProcessingFailure> findByBatchId(String batchId);
}
