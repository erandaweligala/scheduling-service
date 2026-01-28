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

import java.time.LocalDateTime;

@Entity
@Table(name = "PLAN")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Plan {


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "PLAN_ID", length = 64, nullable = false)
    private String planId;

    @Column(name = "PLAN_NAME", length = 64, nullable=false)
    private String planName;

    @Column(name = "PLAN_TYPE", length = 64, nullable = false)
    private String planType;

    @Column(name = "RECURRING_FLAG", nullable = false)
    private Boolean recurringFlag;

    @Column(name = "RECURRING_PERIOD", length = 64)
    private String recurringPeriod;

    @Column(name = "STATUS", length = 64, nullable = false)
    private String status;

    @Column(name = "CONNECTION_TYPE", length = 64, nullable = false)
    private String connectionType;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "QUOTA_PRORATION_FLAG", nullable = false)
    private Boolean quotaProrationFlag;
}
