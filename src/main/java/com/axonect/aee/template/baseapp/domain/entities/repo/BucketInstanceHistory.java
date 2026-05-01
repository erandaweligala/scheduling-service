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
@Table(name = "BUCKET_INSTANCE_HISTORY")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BucketInstanceHistory implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "bucket_instance_history_seq")
    @SequenceGenerator(name = "bucket_instance_history_seq",
            sequenceName = "BUCKET_INSTANCE_HISTORY_SEQ", allocationSize = 1)
    private Long id;

    @Column(name = "ORIGINAL_ID", nullable = false)
    private Long originalId;

    @Column(name = "BUCKET_ID", nullable = false, length = 64)
    private String bucketId;

    @Column(name = "SERVICE_ID", length = 64)
    private Long serviceId;

    @Column(name = "BUCKET_TYPE")
    private String bucketType;

    @Column(name = "RULE", length = 64)
    private String rule;

    @Column(name = "PRIORITY", length = 64)
    private Long priority;

    @Column(name = "INITIAL_BALANCE", length = 64)
    private Long initialBalance;

    @Column(name = "CURRENT_BALANCE", length = 64)
    private Long currentBalance;

    @Column(name = "USAGE", length = 64)
    private Long usage;

    @Column(name = "CARRY_FORWARD")
    private Boolean carryForward;

    @Column(name = "MAX_CARRY_FORWARD")
    private Long maxCarryForward;

    @Column(name = "TOTAL_CARRY_FORWARD")
    private Long totalCarryForward;

    @Column(name = "CARRY_FORWARD_VALIDITY")
    private Integer carryForwardValidity;

    @Column(name = "TIME_WINDOW", length = 64)
    private String timeWindow;

    @Column(name = "CONSUMPTION_LIMIT")
    private Long consumptionLimit;

    @Column(name = "CONSUMPTION_LIMIT_WINDOW")
    private String consumptionLimitWindow;

    @Column(name = "EXPIRATION")
    private LocalDateTime expiration;

    @Column(name = "ORIGINAL_UPDATED_AT")
    private LocalDateTime originalUpdatedAt;

    @Column(name = "IS_UNLIMITED")
    private Boolean isUnlimited;

    @Column(name = "DELETION_REASON", length = 64, nullable = false)
    private String deletionReason;

    @Column(name = "ARCHIVED_AT", nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime archivedAt;

    @Column(name = "BATCH_ID", length = 100)
    private String batchId;
}
