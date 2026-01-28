package com.axonect.aee.template.baseapp.domain.entities.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "PLAN_TO_BUCKET")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanToBucket {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "bucket_quota_seq")
    @SequenceGenerator(name = "bucket_quota_seq", sequenceName = "BUCKET_QUOTA_SEQ", allocationSize = 1)
    private Long id;

    @Column(name = "PLAN_ID", length = 64, nullable = false)
    private String planId;

    @Column(name = "BUCKET_ID", length = 64, nullable = false)
    private String bucketId;

    @Column(name = "INITIAL_QUOTA", nullable = false)
    private Long initialQuota;

    @Column(name = "CARRY_FORWARD", nullable = false)
    private Boolean carryForward;

    @Column(name = "MAX_CARRY_FORWARD")
    private Long maxCarryForward;

    @Column(name = "TOTAL_CARRY_FORWARD")
    private Long totalCarryForward;

    @Column(name = "CARRY_FORWARD_VALIDITY")
    private Integer carryForwardValidity;

    @Column(name = "CONSUMPTION_LIMIT")
    private Long consumptionLimit;

    @Column(name = "CONSUMPTION_LIMIT_WINDOW")
    private String consumptionLimitWindow;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;
}
