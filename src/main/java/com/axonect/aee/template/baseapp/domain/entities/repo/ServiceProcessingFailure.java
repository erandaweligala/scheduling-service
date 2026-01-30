package com.axonect.aee.template.baseapp.domain.entities.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Entity to track service processing failures during recurrent service reactivation.
 * Stores details about failed service instances for monitoring and retry purposes.
 */
@Entity
@Table(name = "SERVICE_PROCESSING_FAILURE")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServiceProcessingFailure implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "service_processing_failure_seq")
    @SequenceGenerator(name = "service_processing_failure_seq", sequenceName = "SERVICE_PROCESSING_FAILURE_SEQ", allocationSize = 1)
    private Long id;

    @Column(name = "SERVICE_INSTANCE_ID")
    private Long serviceInstanceId;

    @Column(name = "USERNAME", length = 64, nullable = false)
    private String username;

    @Column(name = "PLAN_ID", length = 64)
    private String planId;

    @Column(name = "PLAN_NAME", length = 128)
    private String planName;

    @Column(name = "ERROR_TYPE", length = 100)
    private String errorType;

    @Column(name = "ERROR_MESSAGE", length = 4000)
    private String errorMessage;

    @Column(name = "STACK_TRACE", length = 4000)
    private String stackTrace;

    @Column(name = "RETRY_COUNT")
    private Integer retryCount;

    @Column(name = "PROCESSING_STATUS", length = 50)
    private String processingStatus;  // FAILED, PENDING_RETRY, RESOLVED

    @Column(name = "FAILURE_DATE")
    @CreationTimestamp
    private LocalDateTime failureDate;

    @Column(name = "LAST_RETRY_DATE")
    private LocalDateTime lastRetryDate;

    @Column(name = "RESOLVED_DATE")
    private LocalDateTime resolvedDate;

    @Column(name = "BATCH_ID", length = 100)
    private String batchId;

    @Column(name = "ADDITIONAL_INFO", length = 1000)
    private String additionalInfo;
}
