package com.axonect.aee.template.baseapp.domain.entities.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;

@Entity
@Table(name = "BUCKET_INSTANCE")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BucketInstance implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "BUCKET_ID", nullable = false, length = 64)
    private String bucketId;

    @Column(name = "SERVICE_ID", length = 64)
    private Long serviceId;

    @Column(name = "BUCKET_TYPE")
    private String bucketType;

    @Column(name = "RULE", nullable = false, length = 64)
    private String rule;

    @Column(name = "PRIORITY", nullable = false, length = 64)
    private Long priority;

    @Column(name = "INITIAL_BALANCE", nullable = false, length = 64)
    private Long initialBalance;

    @Column(name = "CURRENT_BALANCE", nullable = false, length = 64)
    private Long currentBalance;

    @Column(name = "USAGE", nullable = false, length = 64)
    private Long usage;

    @Column(name = "CARRY_FORWARD", nullable = false)
    private Boolean carryForward;

    @Column(name = "MAX_CARRY_FORWARD")
    private Long maxCarryForward;

    @Column(name = "TOTAL_CARRY_FORWARD")
    private Long totalCarryForward;

    @Column(name = "CARRY_FORWARD_VALIDITY")
    private Integer carryForwardValidity;

    @Column(name = "TIME_WINDOW", nullable = false, length = 64)
    private String timeWindow;

    @Column(name = "CONSUMPTION_LIMIT")
    private Long consumptionLimit;

    @Column(name = "CONSUMPTION_LIMIT_WINDOW")
    private String consumptionLimitWindow;

    @Column(name = "EXPIRATION")
    private LocalDateTime expiration;

    @Column(name = "UPDATED_AT")
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
