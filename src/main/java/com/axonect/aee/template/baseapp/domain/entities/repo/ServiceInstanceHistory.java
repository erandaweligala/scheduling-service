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

@Entity
@Table(name = "SERVICE_INSTANCE_HISTORY")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServiceInstanceHistory implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "service_instance_history_seq")
    @SequenceGenerator(name = "service_instance_history_seq",
            sequenceName = "SERVICE_INSTANCE_HISTORY_SEQ", allocationSize = 1)
    private Long id;

    @Column(name = "ORIGINAL_ID", nullable = false)
    private Long originalId;

    @Column(name = "PLAN_ID", length = 64, nullable = false)
    private String planId;

    @Column(name = "PLAN_NAME", length = 64, nullable = false)
    private String planName;

    @Column(name = "PLAN_TYPE", length = 64, nullable = false)
    private String planType;

    @Column(name = "RECURRING_FLAG", nullable = false)
    private Boolean recurringFlag;

    @Column(name = "USERNAME", length = 64, nullable = false)
    private String username;

    @Column(name = "CYCLE_START_DATE")
    private LocalDateTime serviceCycleStartDate;

    @Column(name = "CYCLE_END_DATE")
    private LocalDateTime serviceCycleEndDate;

    @Column(name = "NEXT_CYCLE_START_DATE")
    private LocalDateTime nextCycleStartDate;

    @Column(name = "SERVICE_START_DATE", nullable = false)
    private LocalDateTime serviceStartDate;

    @Column(name = "EXPIRY_DATE", nullable = false)
    private LocalDateTime expiryDate;

    @Column(name = "STATUS", length = 64, nullable = false)
    private String status;

    @Column(name = "ORIGINAL_CREATED_AT")
    private LocalDateTime originalCreatedAt;

    @Column(name = "ORIGINAL_UPDATED_AT")
    private LocalDateTime originalUpdatedAt;

    @Column(name = "REQUEST_ID", nullable = false)
    private String requestId;

    @Column(name = "IS_GROUP")
    private Boolean isGroup;

    @Column(name = "DELETION_REASON", length = 64, nullable = false)
    private String deletionReason;

    @Column(name = "ARCHIVED_AT", nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime archivedAt;

    @Column(name = "BATCH_ID", length = 100)
    private String batchId;
}
